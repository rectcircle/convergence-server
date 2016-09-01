package com.convergencelabs.server.datastore.domain.mapper

import org.json4s.JsonAST.JObject
import org.scalatest.Finders
import org.scalatest.Matchers
import org.scalatest.WordSpec
import com.convergencelabs.server.domain.model.ot.AppliedArrayRemoveOperation
import com.convergencelabs.server.domain.model.ot.AppliedCompoundOperation
import com.convergencelabs.server.domain.model.ot.AppliedObjectSetOperation
import CompoundOperationMapper.CompoundOperationToODocument
import CompoundOperationMapper.ODocumentToCompoundOperation
import com.orientechnologies.orient.core.record.impl.ODocument
import com.convergencelabs.server.domain.model.data.ObjectValue
import com.convergencelabs.server.domain.model.data.DataValue
import com.convergencelabs.server.domain.model.data.StringValue

class CompoundOperationMapperSpec
    extends WordSpec
    with Matchers {

  "An CompoundOperationMapper" when {
    "when converting compound operations" must {
      "correctly map and unmap a CompoundOperation" in {
        val ops = List(
          AppliedObjectSetOperation("vid1", true, Map().asInstanceOf[Map[String, DataValue]], Some(Map().asInstanceOf[Map[String, DataValue]])),
          AppliedArrayRemoveOperation("vid2", true, 3, Some(StringValue("oldId", "oldValue"))))

        val op = AppliedCompoundOperation(ops)
        val opDoc = op.asODocument
        val reverted = opDoc.asCompoundOperation
        op shouldBe reverted
      }

      "not allow an invalid document class name" in {
        val invalid = new ODocument("SomeClass")
        intercept[IllegalArgumentException] {
          invalid.asCompoundOperation
        }
      }
    }
  }
}
