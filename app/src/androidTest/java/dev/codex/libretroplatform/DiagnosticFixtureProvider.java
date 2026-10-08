package dev.codex.libretroplatform;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Test-APK provider runs independently: use only Java/Android runtime classes here. */
public final class DiagnosticFixtureProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    @Override public String getType(Uri uri) {
        fixtureName(uri);
        return "application/octet-stream";
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        String name = fixtureName(uri);
        String[] columns = projection != null ? projection :
                new String[] { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE };
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = name;
            else if (OpenableColumns.SIZE.equals(columns[i])) row[i] = 0x8000L;
        }
        MatrixCursor cursor = new MatrixCursor(columns);
        cursor.addRow(row);
        return cursor;
    }

    @Override public synchronized ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Fixtures are read-only");
        String name = fixtureName(uri);
        File file = new File(getContext().getCacheDir(), name);
        if (!file.exists()) {
            try (FileOutputStream output = new FileOutputStream(file)) {
                output.write(bytes(getContext(), name));
            } catch (IOException failure) {
                FileNotFoundException error = new FileNotFoundException("Cannot prepare fixture");
                error.initCause(failure);
                throw error;
            }
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    private static String fixtureName(Uri uri) {
        String name = uri.getLastPathSegment();
        if (uri.getPathSegments().size() != 1 || name == null ||
                !name.matches("integration-[0-9a-f-]{36}\\.gb")) {
            throw new IllegalArgumentException("Unknown diagnostic fixture");
        }
        return name;
    }

    /** Original diagnostic code; nonce only changes unused ROM padding. */
    public static byte[] bytes(Context context, String nonce) throws IOException {
        byte[] rom = new byte[0x8000];
        boolean[] written = new boolean[rom.length];
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getAssets().open("diagnostic-battery-gb.hex"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.split("#", 2)[0].trim();
                if (line.isEmpty()) continue;
                String[] fields = line.split("\\s+");
                int offset = Integer.parseInt(fields[0], 16);
                for (int i = 1; i < fields.length; i++) {
                    int address = offset + i - 1;
                    int value = Integer.parseInt(fields[i], 16);
                    if (address < 0 || address >= rom.length || written[address] || value < 0 || value > 255)
                        throw new IOException("Invalid fixture byte");
                    written[address] = true;
                    rom[address] = (byte) value;
                }
            }
        }
        if (nonce != null) {
            try {
                byte[] hash = MessageDigest.getInstance("SHA-256").digest(nonce.getBytes(StandardCharsets.UTF_8));
                System.arraycopy(hash, 0, rom, 0x7f00, hash.length);
            } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
        }
        int header = 0;
        for (int i = 0x134; i <= 0x14c; i++) header = (header - (rom[i] & 255) - 1) & 255;
        rom[0x14d] = (byte) header;
        int checksum = 0;
        for (int i = 0; i < rom.length; i++) if (i != 0x14e && i != 0x14f) checksum += rom[i] & 255;
        rom[0x14e] = (byte) (checksum >> 8);
        rom[0x14f] = (byte) checksum;
        return rom;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
