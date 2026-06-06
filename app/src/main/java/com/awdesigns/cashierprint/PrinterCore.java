package com.awdesigns.cashierprint;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class PrinterCore {
    public static class Result {
        public final boolean success;
        public final String message;
        public final int printedCount;

        public Result(boolean success, String message, int printedCount) {
            this.success = success;
            this.message = message;
            this.printedCount = printedCount;
        }
    }

    private PrinterCore() {}

    public static Result pollAndPrint(Context context) {
        Context app = context.getApplicationContext();
        try {
            SharedPreferences prefs = AppConfig.prefs(app);
            String site = AppConfig.trimSlash(AppConfig.get(prefs, AppConfig.KEY_SITE, AppConfig.DEFAULT_SITE));
            String token = AppConfig.get(prefs, AppConfig.KEY_TOKEN, AppConfig.DEFAULT_TOKEN);
            String url = site + "/api/local_printer.php?action=pending&limit=10&token=" + URLEncoder.encode(token, "UTF-8");

            String response = httpGet(url);
            String trimmed = response == null ? "" : response.trim();
            if (trimmed.startsWith("<")) {
                String msg = "رابط الموقع يرجع صفحة HTML وليس JSON. تأكد من api/local_printer.php";
                AppLog.add(app, msg);
                return new Result(false, msg, 0);
            }

            JSONObject json = new JSONObject(trimmed);
            if (!json.optBoolean("success")) {
                String msg = "خطأ API: " + json.optString("error", "غير معروف");
                AppLog.add(app, msg);
                return new Result(false, msg, 0);
            }

            JSONObject rest = json.optJSONObject("restaurant");
            JSONArray orders = json.optJSONArray("orders");
            if (orders == null || orders.length() == 0) {
                String msg = "لا توجد طلبات جديدة";
                AppLog.add(app, msg);
                return new Result(true, msg, 0);
            }

            int printed = 0;
            AppLog.add(app, "طلبات جديدة: " + orders.length());

            for (int i = 0; i < orders.length(); i++) {
                JSONObject order = orders.getJSONObject(i);
                boolean done = printOrderBitmap(app, order, rest);
                if (done) {
                    if (markPrinted(app, order.optInt("id", 0))) {
                        printed++;
                    }
                }
            }

            String msg = printed > 0 ? "تمت طباعة " + printed + " طلب" : "لم تتم طباعة أي طلب";
            return new Result(true, msg, printed);
        } catch (Exception ex) {
            String msg = "خطأ: " + safe(ex.getMessage());
            AppLog.add(app, msg);
            return new Result(false, msg, 0);
        }
    }

    public static boolean printTest(Context context) {
        Context app = context.getApplicationContext();
        try {
            JSONObject order = new JSONObject();
            order.put("id", 0);
            order.put("order_number", "TEST-PRINT");
            order.put("table_number", "اختبار");
            order.put("created_at", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
            order.put("total_amount", 2000);

            JSONArray items = new JSONArray();
            JSONObject item = new JSONObject();
            item.put("name_ar", "اختبار تنسيق الوصل");
            item.put("qty", 1);
            item.put("price", 2000);
            item.put("subtotal", 2000);
            items.put(item);
            order.put("items_decoded", items);
            order.put("notes", "تجربة طباعة مرتبة بدون قص من الحواف");

            JSONObject rest = new JSONObject();
            rest.put("name", "كافيه مايوركا");

            boolean ok = printOrderBitmap(app, order, rest);
            AppLog.add(app, ok ? "تم إرسال طباعة اختبار" : "فشل اختبار الطابعة");
            return ok;
        } catch (Exception ex) {
            AppLog.add(app, "فشل اختبار الطابعة: " + safe(ex.getMessage()));
            return false;
        }
    }

    private static boolean markPrinted(Context context, int id) {
        if (id <= 0) return false;
        try {
            SharedPreferences prefs = AppConfig.prefs(context);
            String site = AppConfig.trimSlash(AppConfig.get(prefs, AppConfig.KEY_SITE, AppConfig.DEFAULT_SITE));
            String token = AppConfig.get(prefs, AppConfig.KEY_TOKEN, AppConfig.DEFAULT_TOKEN);
            String body = "token=" + URLEncoder.encode(token, "UTF-8") + "&order_id=" + id;
            String response = httpPost(site + "/api/local_printer.php?action=mark_printed", body);
            String trimmed = response == null ? "" : response.trim();
            if (trimmed.startsWith("<")) {
                AppLog.add(context, "تعليم الطلب مطبوع رجع HTML بدلاً من JSON");
                return false;
            }
            JSONObject json = new JSONObject(trimmed);
            if (json.optBoolean("success")) {
                AppLog.add(context, "تم تعليم الطلب مطبوع: " + id);
                return true;
            }
            AppLog.add(context, "فشل تعليم الطلب مطبوع: " + json.optString("error"));
            return false;
        } catch (Exception ex) {
            AppLog.add(context, "خطأ تعليم الطلب مطبوع: " + safe(ex.getMessage()));
            return false;
        }
    }

    private static boolean printOrderBitmap(Context context, JSONObject order, JSONObject rest) {
        try {
            int width = AppConfig.FIXED_PAPER_WIDTH;
            Bitmap bmp = buildReceiptBitmap(order, rest, width);
            byte[] data = buildRasterEscpos(bmp);
            sendToPrinter(context, data);
            AppLog.add(context, "تمت طباعة الطلب: " + order.optString("order_number"));
            return true;
        } catch (Exception ex) {
            AppLog.add(context, "فشل طباعة الطلب " + order.optString("order_number") + ": " + safe(ex.getMessage()));
            return false;
        }
    }

    private static Bitmap buildReceiptBitmap(JSONObject order, JSONObject rest, int width) throws JSONException {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG | Paint.DITHER_FLAG);
        paint.setColor(Color.BLACK);
        paint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));

        JSONArray items = order.optJSONArray("items_decoded");
        int itemCount = items == null ? 0 : items.length();
        int estimatedHeight = Math.max(1050, 720 + (itemCount * 150) + (order.optString("notes", "").length() * 2));
        int safePad = 46;
        int y = 38;

        Bitmap bmp = Bitmap.createBitmap(width, estimatedHeight, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(Color.WHITE);

        String restName = rest != null ? rest.optString("name", "الكافيه") : "الكافيه";

        y = drawCenterWrapped(c, paint, restName, width, safePad, y, 30, true, 2);
        y += 4;
        y = drawCenter(c, paint, "وصل طلب", width, y, 20, false);
        y += 14;
        y = divider(c, paint, width, safePad, y);

        y = drawLabelValue(c, paint, "رقم الطلب", order.optString("order_number", "—"), width, safePad, y);

        String table = order.optString("table_number", "");
        if (!empty(table) && !"null".equals(table)) {
            y = drawCenter(c, paint, "الطاولة: " + table, width, y, 23, true);
            y += 6;
        }

        y = drawCenter(c, paint, "الوقت: " + formatDate24(order.optString("created_at", "")), width, y, 20, false);
        y += 12;
        y = divider(c, paint, width, safePad, y);

        y = drawCenter(c, paint, "المنتجات", width, y, 26, true);
        y += 10;

        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.getJSONObject(i);
                String name = it.optString("name_ar", it.optString("name_en", "منتج"));
                int qty = it.optInt("qty", 1);
                double price = it.optDouble("price", 0);
                double subtotal = it.has("subtotal") ? it.optDouble("subtotal", price * qty) : price * qty;

                y = drawCenterWrapped(c, paint, name, width, safePad, y, 23, true, 3);
                y = drawCenter(c, paint, "الكمية: " + qty + "   |   " + formatMoney(subtotal), width, y, 21, false);
                y += 14;
            }
        }

        y = divider(c, paint, width, safePad, y);
        y = drawCenter(c, paint, "الإجمالي", width, y, 24, false);
        y = drawCenter(c, paint, formatMoney(order.optDouble("total_amount", 0)), width, y, 33, true);

        String notes = order.optString("notes", "");
        if (!empty(notes) && !"null".equals(notes)) {
            y += 12;
            y = divider(c, paint, width, safePad, y);
            y = drawCenter(c, paint, "ملاحظات", width, y, 22, true);
            y = drawCenterWrapped(c, paint, notes, width, safePad, y, 21, false, 4);
        }

        y += 18;
        y = divider(c, paint, width, safePad, y);
        y = drawCenter(c, paint, "شكراً لزيارتكم", width, y, 24, true);
        y += 90; // مسافة قبل القص حتى لا ينقص آخر الوصل

        int finalHeight = Math.min(y, bmp.getHeight());
        return Bitmap.createBitmap(bmp, 0, 0, width, finalHeight);
    }

    private static int drawLabelValue(Canvas c, Paint paint, String label, String value, int width, int pad, int y) {
        y = drawCenter(c, paint, label, width, y, 20, false);
        y = drawCenterWrapped(c, paint, value == null ? "—" : value, width, pad, y, 20, true, 3);
        y += 8;
        return y;
    }

    private static int drawCenter(Canvas c, Paint paint, String text, int width, int y, int size, boolean bold) {
        paint.setTextSize(size);
        paint.setTypeface(Typeface.create(Typeface.SANS_SERIF, bold ? Typeface.BOLD : Typeface.NORMAL));
        paint.setTextAlign(Paint.Align.CENTER);
        c.drawText(text == null ? "" : text, width / 2f, y, paint);
        return y + lineHeight(paint) + 8;
    }

    private static int drawCenterWrapped(Canvas c, Paint paint, String text, int width, int pad, int y, int size, boolean bold, int maxLines) {
        paint.setTextSize(size);
        paint.setTypeface(Typeface.create(Typeface.SANS_SERIF, bold ? Typeface.BOLD : Typeface.NORMAL));
        paint.setTextAlign(Paint.Align.CENTER);

        int maxWidth = width - (pad * 2);
        List<String> lines = wrapText(text == null ? "" : text, paint, maxWidth, maxLines);
        for (String line : lines) {
            c.drawText(line, width / 2f, y, paint);
            y += lineHeight(paint) + 7;
        }
        return y;
    }

    private static List<String> wrapText(String text, Paint paint, int maxWidth, int maxLines) {
        List<String> out = new ArrayList<>();
        String clean = text.replace('\n', ' ').replaceAll("\\s+", " ").trim();
        if (clean.isEmpty()) {
            out.add("");
            return out;
        }
        if (paint.measureText(clean) <= maxWidth) {
            out.add(clean);
            return out;
        }

        String[] words = clean.split(" ");
        StringBuilder line = new StringBuilder();
        for (String word : words) {
            if (out.size() >= maxLines) break;

            if (paint.measureText(word) > maxWidth) {
                if (line.length() > 0) {
                    out.add(line.toString());
                    line.setLength(0);
                    if (out.size() >= maxLines) break;
                }

                StringBuilder chunk = new StringBuilder();
                for (int i = 0; i < word.length(); i++) {
                    String testChunk = chunk.toString() + word.charAt(i);
                    if (paint.measureText(testChunk) <= maxWidth) {
                        chunk.append(word.charAt(i));
                    } else {
                        if (chunk.length() > 0) {
                            out.add(chunk.toString());
                            chunk.setLength(0);
                            if (out.size() >= maxLines) break;
                        }
                        chunk.append(word.charAt(i));
                    }
                }
                if (out.size() >= maxLines) break;
                if (chunk.length() > 0) line.append(chunk);
                continue;
            }

            String test = line.length() == 0 ? word : line + " " + word;
            if (paint.measureText(test) <= maxWidth) {
                line.setLength(0);
                line.append(test);
            } else {
                if (line.length() > 0) out.add(line.toString());
                line.setLength(0);
                line.append(word);
                if (out.size() >= maxLines - 1) break;
            }
        }
        if (out.size() < maxLines && line.length() > 0) out.add(line.toString());

        if (out.isEmpty()) out.add(clean);
        return out;
    }

    private static int lineHeight(Paint paint) {
        Paint.FontMetrics fm = paint.getFontMetrics();
        return (int) Math.ceil(fm.descent - fm.ascent);
    }

    private static int divider(Canvas c, Paint paint, int width, int pad, int y) {
        y += 6;
        paint.setStrokeWidth(2);
        paint.setColor(Color.BLACK);
        c.drawLine(pad, y, width - pad, y, paint);
        return y + 28;
    }

    private static String formatMoney(double v) {
        return String.format(Locale.US, "%,.0f", v) + " د.ع";
    }

    private static String formatDate24(String raw) {
        if (empty(raw) || "null".equals(raw)) {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date());
        }
        String s = raw.trim().replace('T', ' ');
        // MySQL date usually comes as yyyy-MM-dd HH:mm:ss. Keep it 24h and remove seconds for a clean receipt.
        if (s.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}.*")) {
            return s.substring(0, 16);
        }
        if (s.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}.*")) {
            return s.substring(0, 16);
        }
        return s;
    }

    private static byte[] buildRasterEscpos(Bitmap bmp) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{0x1B, 0x40});
        out.write(new byte[]{0x1B, 0x61, 0x01});
        out.write(new byte[]{0x1D, 0x21, 0x00});

        int width = bmp.getWidth();
        int height = bmp.getHeight();
        int bytesPerRow = (width + 7) / 8;

        out.write(new byte[]{
                0x1D, 0x76, 0x30, 0x00,
                (byte)(bytesPerRow & 0xff),
                (byte)((bytesPerRow >> 8) & 0xff),
                (byte)(height & 0xff),
                (byte)((height >> 8) & 0xff)
        });

        for (int y = 0; y < height; y++) {
            for (int xByte = 0; xByte < bytesPerRow; xByte++) {
                int b = 0;
                for (int bit = 0; bit < 8; bit++) {
                    int x = xByte * 8 + bit;
                    if (x < width) {
                        int color = bmp.getPixel(x, y);
                        int r = Color.red(color), g = Color.green(color), bl = Color.blue(color);
                        int gray = (r + g + bl) / 3;
                        if (gray < 165) b |= (0x80 >> bit);
                    }
                }
                out.write(b);
            }
        }

        out.write(new byte[]{0x1B, 0x64, 0x06}); // feed 6 lines
        out.write(new byte[]{0x1D, 0x56, 0x41, 0x10}); // full cut with feed
        return out.toByteArray();
    }

    private static void sendToPrinter(Context context, byte[] data) throws IOException {
        SharedPreferences prefs = AppConfig.prefs(context);
        String ip = AppConfig.get(prefs, AppConfig.KEY_IP, AppConfig.DEFAULT_PRINTER_IP);
        int port = AppConfig.getInt(prefs, AppConfig.KEY_PORT, AppConfig.DEFAULT_PRINTER_PORT, 9100);

        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(ip, port), 6000);
            OutputStream os = socket.getOutputStream();
            os.write(data);
            os.flush();
        } finally {
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    private static String httpGet(String urlStr) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new java.net.URL(urlStr).openConnection();
        con.setConnectTimeout(10000);
        con.setReadTimeout(10000);
        con.setRequestMethod("GET");
        return readResponse(con);
    }

    private static String httpPost(String urlStr, String body) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        HttpURLConnection con = (HttpURLConnection) new java.net.URL(urlStr).openConnection();
        con.setConnectTimeout(10000);
        con.setReadTimeout(10000);
        con.setRequestMethod("POST");
        con.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        con.setDoOutput(true);
        OutputStream os = con.getOutputStream();
        os.write(data);
        os.flush();
        os.close();
        return readResponse(con);
    }

    private static String readResponse(HttpURLConnection con) throws IOException {
        InputStream is = con.getResponseCode() >= 400 ? con.getErrorStream() : con.getInputStream();
        BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        con.disconnect();
        return sb.toString();
    }

    private static boolean empty(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String safe(String s) {
        return s == null ? "غير معروف" : s;
    }
}
