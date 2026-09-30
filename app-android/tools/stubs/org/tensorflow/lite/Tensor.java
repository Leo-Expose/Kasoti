// See the header in org/tensorflow/lite/Interpreter.java for what these stubs are and are not.
package org.tensorflow.lite;

import java.nio.ByteBuffer;

/** The subset of `org.tensorflow.lite.Tensor` that the shape verification reads. */
public class Tensor {
    public int[] shape() { return new int[0]; }

    public DataType dataType() { return DataType.FLOAT32; }

    public ByteBuffer buffer() { return null; }

    public String name() { return ""; }
}
