package com.eclipse.eclat.share;

import android.nfc.cardemulation.HostApduService;
import android.os.Bundle;

/**
 * Makes the phone an NFC tag while a file is being sent: the other phone
 * reads the link to the file like it reads any NFC sticker, even when Éclat
 * is closed on it (and an iPhone does too). Silent the rest of the time.
 */
public class NfcTagService extends HostApduService {
    private Type4Tag tag;
    private boolean told;

    @Override
    public byte[] processCommandApdu(byte[] apdu, Bundle extras) {
        byte[] msg = SendService.ndefMessage;
        if (msg == null) {
            tag = null;
            return Type4Tag.NOT_FOUND;
        }
        boolean selectApp = apdu.length > 4 && (apdu[1] & 0xFF) == 0xA4 && apdu[2] == 0x04;
        if (tag == null || selectApp) {
            tag = new Type4Tag(msg);
            told = false;
        }
        byte[] r = tag.process(apdu);
        if (!told && tag.messageRead()) {
            told = true;
            SendService.onTagRead(this);
        }
        return r;
    }

    @Override
    public void onDeactivated(int reason) {
        tag = null;
    }
}
