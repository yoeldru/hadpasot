// נכתב: 2026-11-02 22:03 (שעון ישראל)
// שירות המרה לאפליקציית "הדפסות": ממיר וורד / אקסל / פאוורפוינט / RTF / HTML ל-PDF דרך Google Drive.
// הגדרה חד-פעמית:
// 1. בצד שמאל: שירותים (+) → Drive API → הוסף.
// 2. שנה את SECRET למטה לסיסמה משלך (ואותה תכניס באפליקציה).
// 3. פריסה → פריסה חדשה → אפליקציית אינטרנט. הפעלה בתור: אני. גישה: כל אחד.
// 4. העתק את כתובת ה-URL (מסתיימת ב-/exec) לאפליקציה: הגדרות → שירות המרה.

const SECRET = 'שנה-אותי';

function doGet(e) {
  const ok = !!(e && e.parameter && e.parameter.key === SECRET);
  return json_({ ok: ok, service: 'hadpasot-converter', error: ok ? '' : 'סיסמה שגויה' });
}

function doPost(e) {
  let tempId = null;
  try {
    const req = JSON.parse(e.postData.contents);
    if (req.key !== SECRET) return json_({ ok: false, error: 'סיסמה שגויה' });
    const blob = Utilities.newBlob(Utilities.base64Decode(req.data), req.mimeType, req.name);
    const file = Drive.Files.create(
      { name: 'hadpasot-temp-' + Date.now(), mimeType: targetMime_(req.mimeType, req.name) },
      blob
    );
    tempId = file.id;
    const pdf = DriveApp.getFileById(tempId).getAs('application/pdf');
    return json_({ ok: true, data: Utilities.base64Encode(pdf.getBytes()) });
  } catch (err) {
    return json_({ ok: false, error: String((err && err.message) || err) });
  } finally {
    if (tempId) {
      try { DriveApp.getFileById(tempId).setTrashed(true); } catch (x) {}
    }
  }
}

function targetMime_(mime, name) {
  const ext = String(name || '').split('.').pop().toLowerCase();
  mime = String(mime || '');
  if (/sheet|excel/.test(mime) || ['xls', 'xlsx', 'ods', 'csv'].indexOf(ext) >= 0) {
    return 'application/vnd.google-apps.spreadsheet';
  }
  if (/presentation|powerpoint/.test(mime) || ['ppt', 'pptx', 'odp'].indexOf(ext) >= 0) {
    return 'application/vnd.google-apps.presentation';
  }
  return 'application/vnd.google-apps.document';
}

function json_(o) {
  return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON);
}

// הרץ פעם אחת מהעורך כדי לאשר הרשאות
function authorize() {
  DriveApp.getRootFolder();
  Drive.Files.list({ pageSize: 1 });
}
