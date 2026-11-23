package com.kingscan.verification.sender;

import android.app.Activity;
import android.os.Bundle;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import java.util.ArrayList;

public final class ShareActivity extends Activity {
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Uri first = Uri.parse("content://com.kingscan.verification.sender.fixtures/retouch-input.jpg");
        Uri second = Uri.parse("content://com.kingscan.verification.sender.fixtures/ocr-input.jpg");
        boolean multiple = getIntent().getBooleanExtra("multiple", false);
        Intent share = new Intent(multiple ? Intent.ACTION_SEND_MULTIPLE : Intent.ACTION_SEND);
        share.setType("image/jpeg");
        share.setClassName("com.kingscan.app", "com.kingscan.app.MainActivity");
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        ClipData clips = ClipData.newUri(getContentResolver(), "verification fixtures", first);
        if (multiple) {
            ArrayList<Uri> list = new ArrayList<>();
            list.add(first); list.add(second);
            share.putParcelableArrayListExtra(Intent.EXTRA_STREAM, list);
            clips.addItem(new ClipData.Item(second));
        } else share.putExtra(Intent.EXTRA_STREAM, first);
        share.setClipData(clips);
        startActivity(share);
        finish();
    }
}
