package com.eclipse.eclat.share;

/**
 * An NFC Forum Type 4 tag (version 2.0) holding one NDEF message, answering
 * the commands of the phone that reads it. The sending phone becomes this
 * tag (Host Card Emulation): any phone, Android or iPhone, reads it like a
 * sticker. Pure Java: tested on the PC.
 */
public final class Type4Tag {
    static final byte[] OK = {(byte) 0x90, 0x00};
    static final byte[] NOT_FOUND = {0x6A, (byte) 0x82};
    static final byte[] WRONG_PARAMS = {0x6B, 0x00};
    static final byte[] INS_NOT_SUPPORTED = {0x6D, 0x00};
    static final byte[] CLA_NOT_SUPPORTED = {0x6E, 0x00};

    /** The NDEF Tag Application. */
    public static final byte[] AID = {(byte) 0xD2, 0x76, 0x00, 0x00, (byte) 0x85, 0x01, 0x01};
    private static final int CC_FILE = 0xE103;
    private static final int NDEF_FILE = 0xE104;
    private static final int MAX_READ = 0xFF; // bytes per READ BINARY answer

    private final byte[] cc;
    private final byte[] ndefFile; // NLEN (2 bytes) + message
    private boolean appSelected;
    private byte[] selected;
    private boolean messageRead;

    public Type4Tag(byte[] ndefMessage) {
        ndefFile = new byte[ndefMessage.length + 2];
        ndefFile[0] = (byte) (ndefMessage.length >> 8);
        ndefFile[1] = (byte) ndefMessage.length;
        System.arraycopy(ndefMessage, 0, ndefFile, 2, ndefMessage.length);
        int maxSize = Math.max(ndefFile.length, 0x0100);
        cc = new byte[]{
                0x00, 0x0F,             // CC length
                0x20,                   // mapping version 2.0
                0x00, (byte) MAX_READ,  // MLe: longest READ BINARY answer
                0x00, (byte) 0xFF,      // MLc: longest UPDATE BINARY command
                0x04, 0x06,             // NDEF File Control TLV
                (byte) 0xE1, 0x04,      // NDEF file id
                (byte) (maxSize >> 8), (byte) maxSize, // max NDEF file size
                0x00,                   // read: free
                (byte) 0xFF,            // write: never
        };
    }

    /** True once the reader has read the whole message. */
    public boolean messageRead() {
        return messageRead;
    }

    public byte[] process(byte[] apdu) {
        if (apdu == null || apdu.length < 4) return WRONG_PARAMS;
        if (apdu[0] != 0x00) return CLA_NOT_SUPPORTED;
        int ins = apdu[1] & 0xFF;
        int p1 = apdu[2] & 0xFF;
        int p2 = apdu[3] & 0xFF;
        if (ins == 0xA4) { // SELECT
            if (apdu.length < 5) return WRONG_PARAMS;
            int lc = apdu[4] & 0xFF;
            if (apdu.length < 5 + lc) return WRONG_PARAMS;
            if (p1 == 0x04) { // by name: the NDEF application
                if (lc != AID.length) return NOT_FOUND;
                for (int i = 0; i < lc; i++) {
                    if (apdu[5 + i] != AID[i]) return NOT_FOUND;
                }
                appSelected = true;
                selected = null;
                return OK;
            }
            if (p1 == 0x00 && lc == 2 && appSelected) { // by file id
                int id = ((apdu[5] & 0xFF) << 8) | (apdu[6] & 0xFF);
                if (id == CC_FILE) {
                    selected = cc;
                    return OK;
                }
                if (id == NDEF_FILE) {
                    selected = ndefFile;
                    return OK;
                }
            }
            return NOT_FOUND;
        }
        if (ins == 0xB0) { // READ BINARY
            if (selected == null) return NOT_FOUND;
            int offset = (p1 << 8) | p2;
            int le = apdu.length >= 5 ? apdu[4] & 0xFF : 0;
            if (le == 0) le = 256;
            le = Math.min(le, MAX_READ);
            if (offset > selected.length) return WRONG_PARAMS;
            int n = Math.min(le, selected.length - offset);
            byte[] out = new byte[n + 2];
            System.arraycopy(selected, offset, out, 0, n);
            out[n] = (byte) 0x90;
            out[n + 1] = 0x00;
            if (selected == ndefFile && offset + n >= ndefFile.length) {
                messageRead = true;
            }
            return out;
        }
        if (ins == 0xD6) { // UPDATE BINARY: the tag is read-only
            return new byte[]{0x69, (byte) 0x82};
        }
        return INS_NOT_SUPPORTED;
    }
}
