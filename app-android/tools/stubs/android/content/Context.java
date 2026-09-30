// See the header in org/tensorflow/lite/Interpreter.java for what these stubs are and are not.
package android.content;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/**
 * The subset of `android.content.Context` that `ModelStore` uses: the app's own files directory
 * and its asset manager, both so a model can be copied out of the APK once and then read as a real
 * file. Neither call needs a permission and neither is stubbed to return null in a way that could
 * hide a mistake — the signatures are the whole point.
 */
public class Context {
    public File getFilesDir() { return null; }

    public AssetManager getAssets() { return null; }

    public static class AssetManager {
        public InputStream open(String path) throws IOException { return null; }
    }
}
