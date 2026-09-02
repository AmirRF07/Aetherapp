# AetherMobile v1.2.8

> Install straight over an older build from the same repository: the signing
> configuration is unchanged. App version <span dir="ltr">1.2.8</span>, version
> code <span dir="ltr">12</span>, engine core <span dir="ltr">1.8.0</span>.

## What's new in v1.2.8

Users reported several connection problems, especially when using chained mode (`Aether → Psiphon`). These problems have now been fixed:

- In chained mode, sometimes only Telegram and Instagram opened. Browsers and many other apps did not work.
- Gemini, ChatGPT and similar AI apps sometimes said there was no internet connection.
- CapCut could fail to open or its effects would not load. It now works in chained mode, including loading effects.
- TikTok could fail to open with Aether alone. Chained mode is recommended for TikTok and CapCut.
- Sometimes the app showed that the connection was active, but browsing and live content did not work properly.
- During long uploads or live sessions, the connection could become very slow or stop passing data. It can now handle this better and recover automatically.
- Ping and connection status were not always reliable. The app now checks the connection more accurately and shows a more trustworthy status.

These fixes apply to plain Aether and, where relevant, to `Aether → Psiphon`.

## Verify what you install

Each release ships per-ABI APKs plus their SHA-256 sums, and the signer
fingerprint is printed in the build log. See `docs/SIGNING.md`.

---

<div dir="rtl">

# ‏AetherMobile نسخهٔ ۱.۲.۸

> روی نسخهٔ قدیمی‌تر از همین مخزن مستقیم نصب می‌شود؛ تنظیمات امضا دست‌نخورده است. نسخهٔ برنامه <span dir="ltr">1.2.8</span>، <span dir="ltr">version code 12</span>، هستهٔ موتور <span dir="ltr">1.8.0</span>.

## تازه‌های نسخهٔ ۱.۲.۸

کاربران چند مشکل در اتصال، به‌ویژه در حالت ترکیبی (`Aether → Psiphon`)، گزارش کرده بودند. این مشکلات حالا برطرف شده‌اند:

- در حالت ترکیبی گاهی فقط تلگرام و اینستاگرام باز می‌شدند و مرورگرها و خیلی از برنامه‌های دیگر کار نمی‌کردند.
- جمینای، چت‌جی‌پی‌تی و برنامه‌های مشابه گاهی پیام می‌دادند که اینترنت در دسترس نیست.
- کپ‌کات ممکن بود باز نشود یا افکت‌هایش بالا نیایند. حالا در حالت ترکیبی باز می‌شود و افکت‌ها هم بارگذاری می‌شوند.
- تیک‌تاک ممکن بود با Aether تنها باز نشود. برای تیک‌تاک و کپ‌کات استفاده از حالت ترکیبی پیشنهاد می‌شود.
- گاهی برنامه متصل نشان داده می‌شد، اما مرور وب و پخش زنده درست کار نمی‌کرد.
- هنگام آپلود طولانی یا پخش زنده، اتصال ممکن بود خیلی کند شود یا انتقال داده متوقف شود. حالا این وضعیت بهتر مدیریت می‌شود و اتصال می‌تواند خودکار برگردد.
- پینگ و وضعیت اتصال همیشه درست نشان داده نمی‌شد. حالا برنامه اتصال را دقیق‌تر بررسی می‌کند و وضعیت قابل‌اعتمادتری نشان می‌دهد.

این اصلاحات برای Aether تنها و، در موارد مربوط، برای حالت ترکیبی `Aether → Psiphon` اعمال شده‌اند.

## چیزی که نصب می‌کنید را بررسی کنید

هر ریلیز فایل‌های APK به‌ازای هر ABI را همراه با مجموع <span dir="ltr">SHA-256</span> آن‌ها منتشر می‌کند و اثر انگشت امضاکننده در لاگ بیلد چاپ می‌شود. <span dir="ltr">`docs/SIGNING.md`</span> را ببینید.

</div>
