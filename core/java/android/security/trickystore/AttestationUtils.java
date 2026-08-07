package android.security.trickystore;

import android.os.Build;
import android.os.SystemProperties;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Log;

import com.android.internal.org.bouncycastle.asn1.*;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.security.*;
import java.security.cert.*;

/**
 * @hide
 */
public final class AttestationUtils {
    private static final String TAG = "AttestationUtils";

    private static final File CONFIG_DIR = new File("/data/adb/tricky_store");
    private static final File BOOT_KEY_FILE = new File(CONFIG_DIR, "boot_key");
    private static final File HBK_FILE = new File(CONFIG_DIR, "hbk");

    private static byte[] sBootKey;
    private static byte[] sBootHash;
    private static volatile boolean sTeeBroken = false;

    /**
     * Device identity fields used in key attestation (tags 710–717).
     * @hide
     */
    public static final class DeviceIdentity {
        public final String brand;
        public final String device;
        public final String product;
        public final String manufacturer;
        public final String model;

        public DeviceIdentity(String brand, String device, String product,
                String manufacturer, String model) {
            this.brand = brand;
            this.device = device;
            this.product = product;
            this.manufacturer = manufacturer;
            this.model = model;
        }
    }

    private AttestationUtils() {}

    /**
     * When {@link Build#FINGERPRINT} is a Google/Pixel spoof (vendor
     * {@code BuildFingerprint} override) but OEM {@code Build.BRAND}/{@code MODEL}
     * still report the real device (e.g. OnePlus), return Pixel identity parsed
     * from the fingerprint so software-generated attestation (TEE-broken /
     * GENERATE path) does not leak the OEM props next to a Google FP.
     *
     * @return parsed identity, or {@code null} when FP is not a google/ spoof
     * @hide
     */
    public static DeviceIdentity identityFromGoogleFingerprint() {
        final String fp = Build.FINGERPRINT;
        if (fp == null || !fp.startsWith("google/")) {
            return null;
        }
        // google/product/device:tag/id/incremental:type/tags
        final String[] parts = fp.split("/");
        if (parts.length < 3) {
            return null;
        }
        final String product = parts[1];
        String device = parts[2];
        final int colon = device.indexOf(':');
        if (colon >= 0) {
            device = device.substring(0, colon);
        }
        // product is often "husky_beta"; prefer bare codename for DEVICE
        final String codename = device.isEmpty()
                ? product.replaceAll("_beta$", "").replaceAll("_user$", "")
                : device;
        final String model = modelForPixelCodename(codename);
        return new DeviceIdentity("google", codename, product, "Google", model);
    }

    /** Best-effort Pixel marketing name for known codenames. @hide */
    public static String modelForPixelCodename(String codename) {
        if (codename == null) return "Pixel";
        switch (codename) {
            case "husky": return "Pixel 8 Pro";
            case "shiba": return "Pixel 8";
            case "akita": return "Pixel 8a";
            case "felix": return "Pixel Fold";
            case "tokay": return "Pixel 9";
            case "caiman": return "Pixel 9 Pro";
            case "komodo": return "Pixel 9 Pro XL";
            case "comet": return "Pixel 9 Pro Fold";
            case "tegu": return "Pixel 9a";
            case "frankel": return "Pixel 10";
            case "blazer": return "Pixel 10 Pro";
            case "mustang": return "Pixel 10 Pro XL";
            case "rango": return "Pixel 10 Pro Fold";
            case "stallion": return "Pixel 10a";
            case "tangorpro": return "Pixel Tablet";
            default: return "Pixel";
        }
    }

    public static void setTeeBroken(boolean broken) {
        sTeeBroken = broken;
    }

    public static boolean isTeeBroken() {
        return sTeeBroken;
    }

    public static byte[] getBootKey() {
        if (sBootKey == null) {
            sBootKey = loadOrCreatePersisted(BOOT_KEY_FILE);
        }
        return sBootKey;
    }

    public static byte[] getBootHash() {
        if (sBootHash == null) {
            sBootHash = getBootHashFromProp();
            if (sBootHash == null) {
                sBootHash = readPersisted(HBK_FILE);
            }
            if (sBootHash == null && !sTeeBroken) {
                sBootHash = extractBootHashFromTee();
                if (sBootHash != null) {
                    writePersisted(HBK_FILE, sBootHash);
                }
            }
            if (sBootHash == null) {
                Log.w(TAG, "Failed to get boot hash from prop, disk, and TEE, using random bytes");
                sBootHash = generateRandomBytes(32);
                writePersisted(HBK_FILE, sBootHash);
            }
        }
        return sBootHash;
    }

    public static void initBootHash() {
        Log.i(TAG, "initBootHash: Starting boot hash initialization");
        byte[] hash = getBootHashFromProp();
        if (hash != null) {
            sBootHash = hash;
            Log.i(TAG, "initBootHash: Boot hash already set from prop: " + bytesToHex(hash));
            return;
        }
        hash = readPersisted(HBK_FILE);
        if (hash != null) {
            sBootHash = hash;
            Log.i(TAG, "initBootHash: Boot hash loaded from disk: " + bytesToHex(hash));
            return;
        }
        Log.i(TAG, "initBootHash: No prop or disk state, attempting TEE extraction");
        hash = extractBootHashFromTee();
        if (hash != null) {
            sBootHash = hash;
            writePersisted(HBK_FILE, hash);
            Log.i(TAG, "initBootHash: TEE extraction successful, setting prop");
            setVbmetaDigestProp(bytesToHex(hash));
        } else {
            Log.e(TAG, "initBootHash: Failed to extract boot hash from TEE");
        }
    }

    private static void setVbmetaDigestProp(String digest) {
        try {
            SystemProperties.set("ro.boot.vbmeta.digest", digest);
            Log.i(TAG, "Set ro.boot.vbmeta.digest property to TEE boot hash");
        } catch (Exception e) {
            Log.e(TAG, "Failed to set vbmeta.digest property", e);
        }
    }

    private static byte[] extractBootHashFromTee() {
        try {
            String alias = "trickystore_attestation_key";
            
            KeyPairGenerator kpg = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
            KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(alias,
                    KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setAttestationChallenge(new byte[32])
                    .build();
            kpg.initialize(spec);
            kpg.generateKeyPair();
            
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            java.security.cert.Certificate[] chain = ks.getCertificateChain(alias);
            
            if (chain == null || chain.length == 0) {
                Log.e(TAG, "No certificate chain from AndroidKeyStore");
                ks.deleteEntry(alias);
                return null;
            }
            
            X509Certificate leafCert = (X509Certificate) chain[0];
            byte[] extValue = leafCert.getExtensionValue("1.3.6.1.4.1.11129.2.1.17");
            
            ks.deleteEntry(alias);
            
            if (extValue == null) {
                Log.e(TAG, "No attestation extension in certificate");
                return null;
            }
            
            ASN1InputStream ais = new ASN1InputStream(extValue);
            ASN1OctetString octet = (ASN1OctetString) ais.readObject();
            ais.close();
            
            ASN1InputStream seqStream = new ASN1InputStream(octet.getOctets());
            ASN1Sequence keyDesc = (ASN1Sequence) seqStream.readObject();
            seqStream.close();
            
            ASN1Sequence teeEnforced = (ASN1Sequence) keyDesc.getObjectAt(7);
            
            for (int i = 0; i < teeEnforced.size(); i++) {
                ASN1TaggedObject tagged = (ASN1TaggedObject) teeEnforced.getObjectAt(i);
                if (tagged.getTagNo() == 704) {
                    ASN1Sequence rootOfTrust = (ASN1Sequence) tagged.getBaseObject();
                    if (rootOfTrust.size() >= 4) {
                        ASN1OctetString bootHashOctet = (ASN1OctetString) rootOfTrust.getObjectAt(3);
                        byte[] hash = bootHashOctet.getOctets();
                        Log.i(TAG, "Extracted boot hash from TEE: " + bytesToHex(hash));
                        return hash;
                    }
                }
            }
            
            Log.e(TAG, "RootOfTrust not found in TEE enforced list");
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Failed to extract boot hash from TEE", e);
            return null;
        }
    }

    public static byte[] getBootHashFromProp() {
        String digest = SystemProperties.get("ro.boot.vbmeta.digest", null);
        if (digest == null || digest.isEmpty() || digest.length() != 64) {
            return null;
        }
        try {
            return hexStringToByteArray(digest);
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse vbmeta.digest", e);
            return null;
        }
    }

    public static int getOsVersion() {
        switch (Build.VERSION.SDK_INT) {
            case Build.VERSION_CODES.Q: return 100000;
            case Build.VERSION_CODES.R: return 110000;
            case Build.VERSION_CODES.S: return 120000;
            case Build.VERSION_CODES.S_V2: return 120100;
            case Build.VERSION_CODES.TIRAMISU: return 130000;
            case Build.VERSION_CODES.UPSIDE_DOWN_CAKE: return 140000;
            case Build.VERSION_CODES.VANILLA_ICE_CREAM: return 150000;
            default: return 160000;
        }
    }

    public static int getAttestVersion() {
        switch (Build.VERSION.SDK_INT) {
            case Build.VERSION_CODES.Q:
            case Build.VERSION_CODES.R:
                return 4;
            case Build.VERSION_CODES.S:
            case Build.VERSION_CODES.S_V2:
                return 100;
            case Build.VERSION_CODES.TIRAMISU:
                return 200;
            case Build.VERSION_CODES.UPSIDE_DOWN_CAKE:
            case Build.VERSION_CODES.VANILLA_ICE_CREAM:
                return 300;
            default:
                return 400;
        }
    }

    public static int getKeymasterVersion() {
        int attestVersion = getAttestVersion();
        return attestVersion == 4 ? 41 : attestVersion;
    }

    public static int getPatchLevel(boolean isLong) {
        return getPatchLevel(isLong, null);
    }

    public static int getPatchLevel(boolean isLong, String[] packages) {
        TrickyStoreService.CustomPatchLevel customLevel =
            TrickyStoreService.getInstance().getCustomPatchLevelForPackage(packages);
        if (customLevel != null && customLevel.system != null) {
            Integer parsed = parsePatchLevel(customLevel.system, isLong);
            if (parsed != null) return parsed;
        }
        return convertPatchLevel(Build.VERSION.SECURITY_PATCH, isLong);
    }

    public static int getVendorPatchLevel(boolean isLong) {
        return getVendorPatchLevel(isLong, null);
    }

    public static int getVendorPatchLevel(boolean isLong, String[] packages) {
        TrickyStoreService.CustomPatchLevel customLevel =
            TrickyStoreService.getInstance().getCustomPatchLevelForPackage(packages);
        if (customLevel != null && customLevel.vendor != null) {
            Integer parsed = parsePatchLevel(customLevel.vendor, isLong);
            if (parsed != null) return parsed;
        }
        return convertPatchLevel(Build.VERSION.SECURITY_PATCH, isLong);
    }

    public static int getBootPatchLevel(boolean isLong) {
        return getBootPatchLevel(isLong, null);
    }

    public static int getBootPatchLevel(boolean isLong, String[] packages) {
        TrickyStoreService.CustomPatchLevel customLevel =
            TrickyStoreService.getInstance().getCustomPatchLevelForPackage(packages);
        if (customLevel != null && customLevel.boot != null) {
            Integer parsed = parsePatchLevel(customLevel.boot, isLong);
            if (parsed != null) return parsed;
        }
        return convertPatchLevel(Build.VERSION.SECURITY_PATCH, isLong);
    }

    private static Integer parsePatchLevel(String value, boolean isLong) {
        if (value == null || value.equalsIgnoreCase("no") || value.equalsIgnoreCase("prop")) {
            return null;
        }

        String normalized = value.replace("-", "");
        try {
            if (normalized.length() == 8) {
                int year = Integer.parseInt(normalized.substring(0, 4));
                int month = Integer.parseInt(normalized.substring(4, 6));
                int day = Integer.parseInt(normalized.substring(6, 8));
                return isLong ? year * 10000 + month * 100 + day : year * 100 + month;
            } else if (normalized.length() == 6) {
                int year = Integer.parseInt(normalized.substring(0, 4));
                int month = Integer.parseInt(normalized.substring(4, 6));
                return isLong ? year * 10000 + month * 100 : year * 100 + month;
            }
        } catch (NumberFormatException e) {
            Log.e(TAG, "Failed to parse patch level: " + value, e);
        }
        return null;
    }

    public static int convertPatchLevel(String patchString, boolean isLong) {
        try {
            String[] parts = patchString.split("-");
            if (isLong && parts.length >= 3) {
                return Integer.parseInt(parts[0]) * 10000 + 
                       Integer.parseInt(parts[1]) * 100 + 
                       Integer.parseInt(parts[2]);
            } else if (parts.length >= 2) {
                return Integer.parseInt(parts[0]) * 100 + Integer.parseInt(parts[1]);
            }
        } catch (Exception e) {
            Log.e(TAG, "Invalid patch level format: " + patchString, e);
        }
        return 202404;
    }

    public static byte[] computeModuleHash() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(new byte[0]);
        } catch (Exception e) {
            Log.e(TAG, "Failed to compute module hash", e);
            return new byte[32];
        }
    }

    private static byte[] generateRandomBytes(int length) {
        byte[] bytes = new byte[length];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    /**
     * Loads a persisted value from {@code file}, generating and persisting
     * a fresh 32-byte random value on first run so it stays stable across
     * daemon restarts and reboots instead of being re-randomized every time.
     */
    private static byte[] loadOrCreatePersisted(File file) {
        byte[] existing = readPersisted(file);
        if (existing != null) {
            return existing;
        }
        byte[] fresh = generateRandomBytes(32);
        writePersisted(file, fresh);
        return fresh;
    }

    private static byte[] readPersisted(File file) {
        if (!file.isFile()) {
            return null;
        }
        try {
            byte[] data = Files.readAllBytes(file.toPath());
            if (data.length == 32) {
                return data;
            }
            Log.w(TAG, "Persisted value at " + file + " has unexpected length "
                    + data.length + ", ignoring");
        } catch (IOException e) {
            Log.w(TAG, "Failed to read persisted value from " + file, e);
        }
        return null;
    }

    private static void writePersisted(File file, byte[] data) {
        try {
            if (!CONFIG_DIR.isDirectory() && !CONFIG_DIR.mkdirs()) {
                Log.w(TAG, "Failed to create " + CONFIG_DIR);
                return;
            }
            File tmp = new File(CONFIG_DIR, file.getName() + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(data);
            }
            if (!tmp.renameTo(file)) {
                Log.w(TAG, "Failed to rename " + tmp + " to " + file);
            } else {
                file.setReadable(true, true);
                file.setWritable(true, true);
            }
        } catch (IOException e) {
            Log.w(TAG, "Failed to persist value to " + file, e);
        }
    }

    private static byte[] hexStringToByteArray(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
