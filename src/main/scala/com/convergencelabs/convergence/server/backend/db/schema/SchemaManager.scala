/*
 * Copyright (c) 2019 - Convergence Labs, Inc.
 *
 * This file is part of the Convergence Server, which is released under
 * the terms of the GNU General Public License version 3 (GPLv3). A copy
 * of the GPLv3 should have been provided along with this file, typically
 * located in the "LICENSE" file, which is part of this source code package.
 * Alternatively, see <https://www.gnu.org/licenses/gpl-3.0.html> for the
 * full text of the GPLv3 license, if it was not provided.
 */

package com.convergencelabs.convergence.server.backend.db.schema

import com.convergencelabs.convergence.server.backend.db.schema.SchemaMetaDataRepository.ReadError
import com.convergencelabs.convergence.server.util.{ExceptionUtils, Sha256HashValidator}
import grizzled.slf4j.Logging

import java.time.Instant
import scala.util.{Failure, Success}

/**
 * The [[SchemaManager]] is the implements the core logic for installing and
 * upgrading schemas. It leverages the [[SchemaMetaDataRepository]] class as
 * its source of information of what versions are installable and upgradable
 * too.
 *
 * @param schemaMetaDataRepository The repository containing information on
 *                                 what versions are available.
 * @param schemaStatePersistence   Provides information on what deltas have
 *                                 been applied to the schema and allows
 *                                 the recording of deltas as they are
 *                                 applied.
 * @param deltaApplicator          A delegate that actually applies deltas
 *                                 to the schema
 */
private[schema] class SchemaManager(schemaMetaDataRepository: SchemaMetaDataRepository,
                                    schemaStatePersistence: SchemaStatePersistence,
                                    deltaApplicator: DeltaApplicator) extends Logging {

  import SchemaManager._

  def latestAvailableVersion(): Either[RepositoryError, SchemaVersion] = {
    for {
      versions <- readVersionIndex
      currentVersion = versions.currentVersion
      semVer <- SchemaVersion
        .parse(currentVersion)
        .left.map(_ => RepositoryError("Could not parse semantic version: " + currentVersion))
    } yield semVer
  }

  def currentlyInstalledVersion(): Either[StatePersistenceError, SchemaVersion] = {
    for {
      installedVersion <- schemaStatePersistence.installedVersion().fold(
        { cause =>
          val message = "Could not get currently installed schema version"
          error(message, cause)
          Left(StatePersistenceError(message))
        },
        version => Right(version)
      )
      semVer <- installedVersion.map(v => SchemaVersion.parse(v)
        .map(sv => sv)
        .left.map(_ => StatePersistenceError("Could not parse semantic version: " + installedVersion)))
        .getOrElse(Left(StatePersistenceError("The schema is not installed")))
    } yield semVer
  }

  /**
   * Installs the latest schema into a fresh database instance.
   *
   * @return Right if successful, or a Left(error) if unsuccessful.
   */
  def install(): Either[SchemaInstallError, Unit] = {
    for {
      versions <- readVersionIndex
      version = versions.currentVersion
      manifest <- readSchemaManifest(version)
      schemaDelta <- readSchemaDelta(version)
      _ <- {
        if (manifest.released) {
          validateHash(manifest.schemaSha256, schemaDelta.script)
        } else {
          Right(())
        }
      }
      _ <- installSchema(version, manifest, schemaDelta)
      _ <- recordNewVersion(version, Instant.now())
    } yield ()
  }

  def recordUpgradeStarting(): Either[StatePersistenceError, Unit] = {
    schemaStatePersistence
      .recordUpgrading().map(_ => Right(())).getOrElse(Left(StatePersistenceError("Could not record upgrade starting")))
  }

  def recordUpgradeFailure(message: String): Either[StatePersistenceError, Unit] = {
    schemaStatePersistence
      .recordUpgradeFailure(message).map(_ => Right(())).getOrElse(Left(StatePersistenceError("Could not record upgrade failure")))
  }

  /**
   * Upgrades an existing schema to the latest version.
   *
   * @return Right if successful, or a Left(error) if unsuccessful.
   */
  def upgrade(): Either[SchemaUpgradeError, Unit] = {
    (for {
      versions <- readVersionIndex
      version = versions.currentVersion
      manifest <- readSchemaManifest(version)
      appliedDeltas <- appliedDeltas()
      neededDeltaIds = computeNeededDeltas(appliedDeltas.map(_.id), manifest.deltas)
      deltas <- readAndValidateDeltas(neededDeltaIds)
      _ <- recordUpgradeStarting()
      _ <- applyDeltas(deltas, version)
      _ <- recordNewVersion(version, Instant.now())
    } yield ())
      .left.map{err =>
      err match {
        case DeltaApplicationError(cause) =>
          recordUpgradeFailure(cause.map(_.getMessage).getOrElse("Unknown error applying delta during upgrade"))
        case _: InvalidHashError =>
          recordUpgradeFailure("A delta failed hash validation")
        case RepositoryError(message) =>
          recordUpgradeFailure(message)
        case StatePersistenceError(message) =>
          recordUpgradeFailure(message)
      }
      err
    }
  }

  private[this] def installSchema(version: String, manifest: SchemaVersionManifest, schemaDelta: InstallDeltaAndScript): Either[DeltaApplicationError, Unit] = {
    val implicitDeltas: List[UpgradeDeltaId] =
      List(UpgradeDeltaId(InstallDeltaReservedName, Some(InstallDeltaReservedTag))) ++
        manifest.deltas.map(_.toDeltaId.copy(tag = Some(InstallDeltaReservedTag)))
    (for {
      _ <- deltaApplicator.applyDeltaToSchema(schemaDelta.delta)
      _ <- schemaStatePersistence.recordImplicitDeltasFromInstall(implicitDeltas, version, schemaDelta.script)
    } yield ())
      .fold({ cause =>
        error("Error installing schema", cause)
        Left(DeltaApplicationError(Some(cause)))
      },
        _ => Right(())
      )

  }

  private[this] def computeNeededDeltas(currentDeltas: List[String], desiredDeltas: List[UpgradeDeltaEntry]): List[UpgradeDeltaEntry] =
    desiredDeltas.
      filter(d => !currentDeltas.contains(d.id))

  private[this] def applyDeltas(deltas: List[UpgradeDeltaAndScript], appliedForVersion: String): Either[DeltaApplicationError, Unit] = {
    val iter = deltas.iterator
    var cur: Either[DeltaApplicationError, Unit] = Right(())
    while (iter.hasNext && cur.isRight) {
      cur = applyDelta(iter.next(), appliedForVersion)
    }
    cur
  }

  private[this] def applyDelta(delta: UpgradeDeltaAndScript, appliedForVersion: String): Either[DeltaApplicationError, Unit] = {
    deltaApplicator.applyDeltaToSchema(delta.delta) match {
      case Failure(exception) =>
        recordDeltaFailure(delta, exception, appliedForVersion)
        Left(DeltaApplicationError(Some(exception)))
      case Success(_) =>
        logger.debug(s"Delta '${delta.id}' applied successfully")
        recordDeltaSuccess(delta, appliedForVersion).left.map(_ => DeltaApplicationError())
    }
  }


  private[this] def recordDeltaFailure(delta: UpgradeDeltaAndScript, cause: Throwable, appliedForVersion: String): Unit = {
    logger.error(s"Error applying Delta '${delta.id}'", cause)
    val error = ExceptionUtils.stackTraceToString(cause)
    schemaStatePersistence.recordDeltaFailure(delta, error, appliedForVersion)
  }

  private[this] def recordDeltaSuccess(delta: UpgradeDeltaAndScript, appliedForVersion: String): Either[StatePersistenceError, Unit] = {
    schemaStatePersistence.recordDeltaSuccess(delta, appliedForVersion) match {
      case Failure(cause) =>
        val message = s"Storing Delta '${delta.id}' failed"
        logger.error(message, cause)
        Left(StatePersistenceError(message))
      case Success(_) =>
        Right(())
    }
  }

  private[this] def readAndValidateDeltas(neededDeltas: List[UpgradeDeltaEntry]): Either[SchemaUpgradeError, List[UpgradeDeltaAndScript]] = {
    val iter = neededDeltas.iterator
    var result: Either[SchemaUpgradeError, List[UpgradeDeltaAndScript]] = Right(List())
    while (iter.hasNext && result.isRight) {
      val entry = iter.next()
      result = for {
        delta <- readDelta(entry.toDeltaId)
        _ <- validateHash(entry.sha256, delta.script)
        updated <- result.map { currentList =>
          currentList :+ delta
        }
      } yield updated
    }

    result
  }

  private[this] def readVersionIndex: Either[RepositoryError, SchemaVersionIndex] =
    schemaMetaDataRepository
      .readVersions()
      .left.map(e => mapReadError(e, "version index"))

  private[this] def readSchemaManifest(version: String): Either[RepositoryError, SchemaVersionManifest] =
    schemaMetaDataRepository
      .readSchemaVersionManifest(version)
      .left.map(e => mapReadError(e, "schema version manifest"))

  private[this] def readSchemaDelta(version: String): Either[RepositoryError, InstallDeltaAndScript] =
    schemaMetaDataRepository.readFullSchema(version)
      .left.map(e => mapReadError(e, "schema installation delta"))

  private[this] def readDelta(deltaId: UpgradeDeltaId): Either[RepositoryError, UpgradeDeltaAndScript] =
    schemaMetaDataRepository.readDelta(deltaId)
      .left.map(e => mapReadError(e, s"delta $deltaId"))

  private[this] def validateHash(expectedHash: String, script: String): Either[DeltaValidationError, Unit] =
    Sha256HashValidator
      .validateHash(script, expectedHash)
      .left.map(error => InvalidHashError(error.expected, error.actual))

  private[this] def appliedDeltas(): Either[StatePersistenceError, List[UpgradeDeltaId]] = {
    schemaStatePersistence
      .appliedDeltas()
      .fold(
        { cause =>
          val message = "Could not retrieve the applied deltas for the database"
          error(message, cause)
          Left(StatePersistenceError(message + "\n\n" + ExceptionUtils.stackTraceToString(cause)))
        },
        deltas => Right(deltas)
      )
  }

  private[this] def recordNewVersion(version: String, date: Instant): Either[StatePersistenceError, Unit] = {
    schemaStatePersistence
      .recordNewVersion(version, date)
      .fold(
        { cause =>
          val message = "Could not store new version record for the database"
          error(message, cause)
          Left(StatePersistenceError(message + "\n\n" + ExceptionUtils.stackTraceToString(cause)))
        },
        _ => Right(())
      )
  }

  private[this] def mapReadError(readError: ReadError, whatWasRead: String): RepositoryError =
    readError match {
      case SchemaMetaDataRepository.FileNotFoundError(path) =>
        RepositoryError(s"The $whatWasRead was not found: " + path)
      case SchemaMetaDataRepository.ParsingError(message) =>
        RepositoryError(s"The $whatWasRead could not be parsed: " + message)
      case SchemaMetaDataRepository.UnknownError =>
        RepositoryError(s"An unknown error occurred reading the $whatWasRead.")
    }
}

object SchemaManager {

  val InstallDeltaReservedName = "install"
  val InstallDeltaReservedTag = "install"

  sealed trait SchemaInstallError

  sealed trait SchemaUpgradeError

  final case class DeltaApplicationError(cause: Option[Throwable] = None) extends SchemaInstallError with SchemaUpgradeError

  sealed trait DeltaValidationError extends SchemaInstallError with SchemaUpgradeError

  final case class RepositoryError(message: String) extends SchemaInstallError with SchemaUpgradeError

  final case class InvalidHashError(expected: String, actual: String) extends DeltaValidationError

  final case class StatePersistenceError(message: String) extends SchemaInstallError with SchemaUpgradeError

}
