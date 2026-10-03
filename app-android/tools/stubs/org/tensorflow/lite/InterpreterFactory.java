// WHY THIS STUB EXISTS — the full rationale is in `InterpreterApi.java`; read that first.
//
// `:app-android` dropped `tensorflow-lite-support:0.4.4` (it re-shipped 21 `org.tensorflow.lite.*`
// classes that LiteRT's `litert-api:1.0.1` also ships, failing `checkDebugDuplicateClasses`), and
// LiteRT's `litert-api` deleted the concrete `Interpreter` class. Instances are now made through
// this factory, which is the real LiteRT spelling of the old `Interpreter(buffer, options)`
// constructor.
//
// Signatures read out of the real artifact rather than guessed, with
//   javap -cp … litert-api-1.0.1/jars/classes.jar org.tensorflow.lite.InterpreterFactory
//   public org.tensorflow.lite.InterpreterFactory();
//   public org.tensorflow.lite.InterpreterApi create(java.io.File, org.tensorflow.lite.InterpreterApi$Options);
//   public org.tensorflow.lite.InterpreterApi create(java.nio.ByteBuffer, org.tensorflow.lite.InterpreterApi$Options);
// so this stub cannot drift from what the app actually resolves.
//
// A static `InterpreterApi.create(ByteBuffer, Options)` also exists in the real artifact; the
// binding uses the factory because an instance method cannot be confused with a Kotlin `object`
// accessor, and `InterpreterFactory` is what the LiteRT migration guidance names.

package org.tensorflow.lite;

import java.io.File;
import java.nio.ByteBuffer;

public class InterpreterFactory {

    public InterpreterFactory() {}

    public InterpreterApi create(File model, InterpreterApi.Options options) { return null; }

    public InterpreterApi create(ByteBuffer buffer, InterpreterApi.Options options) { return null; }
}