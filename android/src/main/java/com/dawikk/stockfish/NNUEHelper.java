// android/src/main/java/com/dawikk/stockfish/NNUEHelper.java
package com.dawikk.stockfish;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class NNUEHelper {
    private static final String TAG = "NNUEHelper";
    
    /**
     * Copies NNUE files from assets to the app's files directory where they can be accessed by the native code
     * @param context Android context
     * @return true if successful, false otherwise
     */
    public static boolean copyNNUEFilesFromAssets(Context context) {
        try {
            AssetManager assetManager = context.getAssets();
            String[] files = {"nn-1111cefa1111.nnue", "nn-37f18f62d772.nnue"};
            
            for (String filename : files) {
                InputStream in = null;
                OutputStream out = null;
                try {
                    in = assetManager.open(filename);
                    File outFile = new File(context.getFilesDir(), filename);
                    out = new FileOutputStream(outFile);
                    
                    byte[] buffer = new byte[1024];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                    
                    Log.i(TAG, "Copied NNUE file: " + outFile.getAbsolutePath());
                } catch (IOException e) {
                    Log.e(TAG, "Failed to copy asset file: " + filename, e);
                    return false;
                } finally {
                    if (in != null) {
                        try {
                            in.close();
                        } catch (IOException e) {
                            // ignore
                        }
                    }
                    if (out != null) {
                        try {
                            out.close();
                        } catch (IOException e) {
                            // ignore
                        }
                    }
                }
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error copying NNUE files", e);
            return false;
        }
    }
    
    /**
     * Returns the absolute path to the NNUE file in the app's files directory
     * @param context Android context
     * @param filename NNUE filename
     * @return absolute path to the NNUE file
     */
    public static String getNNUEFilePath(Context context, String filename) {
        File file = new File(context.getFilesDir(), filename);
        return file.getAbsolutePath();
    }
}