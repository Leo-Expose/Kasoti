// See the header in org/tensorflow/lite/Interpreter.java for what these stubs are and are not.
package org.tensorflow.lite;

/**
 * The subset of `org.tensorflow.lite.DataType`.
 *
 * `FLOAT32` is the only member used, and it is load-bearing: the input tensor of
 * `blazeface_short.tflite` is unquantised float, and `TfliteFaceDetector.verifyShapes` refuses
 * anything else rather than letting a quantised buffer reach inference.
 */
public enum DataType {
    FLOAT32, FLOAT16, UINT8, INT8, INT32, INT64, STRING, BOOL, COMPLEX64, INT16, FLOAT64
}
