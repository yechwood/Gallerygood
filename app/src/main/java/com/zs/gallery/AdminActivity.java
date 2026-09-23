package com.zs.gallery;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.*;

public class AdminActivity extends Activity {
    static final String DEFAULT_EMAIL = "kedemwoodlake@gmail.com";
    static final String PREFS = "gallery_guard";
    static final String EMAIL_SEND_URL = "https://api.formsubmit.cc/submit";
    static final long RESEND_DELAY_MS = 60_000L;

    ExecutorService mailExecutor = Executors.newSingleThreadExecutor();
    String pendingCode;
    long codeSentAt;
    int failedAttempts;

    int dp(float v) { return (int)(v * getResources().getDisplayMetrics().density + .5f); }

    String getGuardianEmail() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getString("email", DEFAULT_EMAIL);
    }

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        showAuthorization();
    }

    @Override protected void onDestroy() {
        mailExecutor.shutdownNow();
        super.onDestroy();
    }

    TextView t(String s, int size) {
        TextView v = new TextView(this);
        v.setText(s); v.setTextColor(Color.WHITE); v.setTextSize(size);
        v.setPadding(0, dp(8), 0, dp(8));
        return v;
    }

    GradientDrawable rounded(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color); g.setCornerRadius(dp(radius));
        return g;
    }

    void base(LinearLayout r) {
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(dp(28), dp(36), dp(28), dp(28));
        r.setBackgroundColor(Color.rgb(18,18,20));
    }

    void showAuthorization() {
        LinearLayout r = new LinearLayout(this);
        base(r);

        TextView title = t("Gallery settings", 30);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        r.addView(title);

        TextView sub = t("Email verification", 16);
        sub.setTextColor(Color.rgb(132,245,212));
        r.addView(sub);
        r.addView(t("Enter the authorized email address to receive a one-time verification code.", 15));

        EditText email = new EditText(this);
        email.setHint("Email address");
        email.setText(getGuardianEmail());
        email.setTextColor(Color.WHITE); email.setHintTextColor(Color.GRAY);
        email.setInputType(33); email.setSingleLine(true);
        email.setBackground(rounded(Color.rgb(35,35,39), 14));
        email.setPadding(dp(14),0,dp(14),0);
        r.addView(email, new LinearLayout.LayoutParams(-1, dp(54)));

        Button send = new Button(this);
        send.setText("Get verification code");
        send.setTextColor(Color.WHITE);
        send.setBackground(rounded(Color.rgb(45,110,95), 18));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, dp(50));
        sp.topMargin = dp(14);
        r.addView(send, sp);

        TextView status = t("A new code expires after 10 minutes.", 13);
        status.setTextColor(Color.rgb(160,160,170));
        r.addView(status);

        EditText code = new EditText(this);
        code.setHint("6-digit code");
        code.setTextColor(Color.WHITE); code.setHintTextColor(Color.GRAY);
        code.setInputType(2); code.setSingleLine(true);
        code.setBackground(rounded(Color.rgb(35,35,39), 14));
        code.setPadding(dp(14),0,dp(14),0);
        code.setVisibility(View.GONE);
        r.addView(code, new LinearLayout.LayoutParams(-1, dp(54)));

        Button verify = new Button(this);
        verify.setText("Verify");
        verify.setTextColor(Color.WHITE);
        verify.setBackground(rounded(Color.rgb(45,45,50), 18));
        verify.setVisibility(View.GONE);
        r.addView(verify, new LinearLayout.LayoutParams(-1, dp(50)));

        send.setOnClickListener(v -> {
            String entered = email.getText().toString().trim();
            String authorized = getGuardianEmail();
            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(entered).matches()
                    || !authorized.equalsIgnoreCase(entered)) {
                Toast.makeText(this, "Enter the authorized email address.", Toast.LENGTH_LONG).show();
                return;
            }

            long now = System.currentTimeMillis();
            if (now - codeSentAt < RESEND_DELAY_MS) {
                long left = (RESEND_DELAY_MS - (now-codeSentAt) + 999) / 1000;
                Toast.makeText(this, "Please wait " + left + " seconds.", Toast.LENGTH_LONG).show();
                return;
            }

            pendingCode = String.format(Locale.US, "%06d", new Random().nextInt(1_000_000));
            codeSentAt = now;
            failedAttempts = 0;
            send.setEnabled(false);
            status.setText("Sending…");

            final String codeToSend = pendingCode;
            mailExecutor.execute(() -> {
                SendResult result = sendCodeEmail(codeToSend, authorized);
                runOnUiThread(() -> {
                    send.setEnabled(true);
                    if (result.ok) {
                        code.setVisibility(View.VISIBLE);
                        verify.setVisibility(View.VISIBLE);
                        code.requestFocus();
                        ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE))
                                .showSoftInput(code, InputMethodManager.SHOW_IMPLICIT);
                        status.setText("Code sent. Check your email.");
                    } else {
                        pendingCode = null;
                        status.setText(result.message);
                        Toast.makeText(this, result.message, Toast.LENGTH_LONG).show();
                    }
                });
            });
        });

        verify.setOnClickListener(v -> {
            String entered = code.getText().toString().trim();
            if (pendingCode == null || System.currentTimeMillis()-codeSentAt > 10*60_000L) {
                Toast.makeText(this, "That code has expired.", Toast.LENGTH_LONG).show();
                return;
            }
            if (++failedAttempts > 5) {
                pendingCode = null;
                Toast.makeText(this, "Too many incorrect attempts.", Toast.LENGTH_LONG).show();
                return;
            }
            if (pendingCode.equals(entered)) {
                pendingCode = null;
                showSettings();
            } else {
                Toast.makeText(this, "Incorrect code.", Toast.LENGTH_LONG).show();
            }
        });

        setContentView(r);
    }

    SendResult sendCodeEmail(String code, String recipient) {
        HttpURLConnection c = null;
        try {
            URL u = new URL(EMAIL_SEND_URL);
            c = (HttpURLConnection)u.openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(15_000);
            c.setReadTimeout(20_000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            c.setRequestProperty("Accept", "application/json");

            String body = enc("_domain") + "=" + enc("github.com/yechwood/Gallerygood")
                    + "&" + enc("_to") + "=" + enc(recipient)
                    + "&" + enc("name") + "=" + enc("Gallery")
                    + "&" + enc("email") + "=" + enc(recipient)
                    + "&" + enc("_subject") + "=" + enc("Gallery settings verification code")
                    + "&" + enc("_template") + "=" + enc("table")
                    + "&" + enc("message") + "=" + enc(
                            "Your Gallery settings verification code is: " + code
                            + "\n\nThis code expires in 10 minutes.");

            try(OutputStream out = c.getOutputStream()) {
                out.write(body.getBytes("UTF-8"));
            }

            int status = c.getResponseCode();
            InputStream stream = status >= 400 ? c.getErrorStream() : c.getInputStream();
            String response = stream == null ? "" : readAll(stream);
            String lower = response.toLowerCase(Locale.US);

            if (status >= 200 && status < 300 && !lower.contains("\"success\":false")
                    && !lower.contains("\"error\"")) {
                return new SendResult(true, "Code sent. Check your email.");
            }
            return new SendResult(false, "Email service rejected the request.");
        } catch (Exception e) {
            return new SendResult(false, "Email could not be sent. Check your connection.");
        } finally {
            if (c != null) c.disconnect();
        }
    }

    String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); }
        catch (Exception e) { return s; }
    }

    String readAll(InputStream in) throws IOException {
        try(BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
            StringBuilder s = new StringBuilder();
            String line;
            while((line=br.readLine()) != null) s.append(line);
            return s.toString();
        }
    }

    void showSettings() {
        LinearLayout r = new LinearLayout(this);
        base(r);

        TextView title = t("Video restriction", 28);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        r.addView(title);

        r.addView(t("Verification email", 16));
        EditText settingsEmail = new EditText(this);
        settingsEmail.setHint("Email address");
        settingsEmail.setText(getGuardianEmail());
        settingsEmail.setTextColor(Color.WHITE); settingsEmail.setHintTextColor(Color.GRAY);
        settingsEmail.setInputType(33); settingsEmail.setSingleLine(true);
        settingsEmail.setBackground(rounded(Color.rgb(35,35,39), 14));
        settingsEmail.setPadding(dp(14),0,dp(14),0);
        r.addView(settingsEmail, new LinearLayout.LayoutParams(-1, dp(54)));

        TextView emailInfo = t("Future secret-code verification messages will be sent to this address.", 13);
        emailInfo.setTextColor(Color.rgb(160,160,170));
        r.addView(emailInfo);

        r.addView(t("Maximum video duration", 16));
        SeekBar limit = new SeekBar(this);
        limit.setMax(59);
        long current = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getLong("max_ms", 5 * 60_000L);
        int minutes = (int)Math.max(1, Math.min(60, current/60_000L));
        limit.setProgress(minutes-1);
        r.addView(limit, new LinearLayout.LayoutParams(-1, dp(50)));

        TextView value = t(minutes + " minutes", 18);
        value.setTextColor(Color.rgb(132,245,212));
        r.addView(value);

        limit.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean from) {
                value.setText((p+1) + " minutes");
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });

        r.addView(t("Videos longer than this limit are rejected before a player is created.", 13));

        Button save = new Button(this);
        save.setText("Save");
        save.setTextColor(Color.WHITE);
        save.setBackground(rounded(Color.rgb(45,110,95), 18));
        r.addView(save, new LinearLayout.LayoutParams(-1, dp(50)));

        save.setOnClickListener(v -> {
            String newEmail = settingsEmail.getText().toString().trim();
            if (!android.util.Patterns.EMAIL_ADDRESS.matcher(newEmail).matches()) {
                Toast.makeText(this, "Enter a valid email address.", Toast.LENGTH_LONG).show();
                return;
            }
            long ms = (limit.getProgress()+1) * 60_000L;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putLong("max_ms", ms)
                    .putString("email", newEmail)
                    .apply();
            Toast.makeText(this, "Settings saved.", Toast.LENGTH_SHORT).show();
            finish();
        });

        setContentView(r);
    }

    static class SendResult {
        boolean ok; String message;
        SendResult(boolean o, String m) { ok=o; message=m; }
    }
}
