package com.convergencelabs.server.domain.model.ot

private[ot] object ObjectSetPropertySetTF extends OperationTransformationFunction[ObjectSetPropertyOperation, ObjectSetOperation] {
  def transform(s: ObjectSetPropertyOperation, c: ObjectSetOperation): (ObjectSetPropertyOperation, ObjectSetOperation) = {
      (s.copy(noOp = true), c)
  }
}