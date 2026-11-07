# Family GPS Tracker

Family members ke liye simple location-sharing app (Flutter + Firebase), sirf Android.

- Pehli baar app me 1-2 apna mobile number daalo aur sharing shuru karo. Uske baad
  app background me (app band hone ke baad bhi) raw GPS Firebase me bhejta rehta hai.
- Dekhne wala number daalta hai, **"Location fetch karo"** dabata hai, aur
  **official Google Maps app** khul jaata hai (Android `geo:` intent).
  **Google Maps SDK nahi, Maps API key nahi.**
- Hamburger menu se apna number kabhi bhi edit/add kar sakte ho. Number badalne
  ke baad bhi purana number fetch karne par kaam karta rahega.
- Internet band ho to SMS se turant aakhri location bhej di jaati hai, family
  ke baaki registered numbers par — kuch manually dalna nahi padta, app
  khud apne family ke numbers jaan jaata hai.
- **Offline member ki location (SMS request/reply):** agar jiski location fetch
  kar rahe ho uska net band hai (location 3 minute se purani), to fetch karne
  wale ka app us member ke number par apne aap SMS bhejta hai
  ("aap offline ho, aapki GPS location chahiye"). Member ka phone ye SMS khud
  padhta hai (app band ho tab bhi) aur apni GPS location SMS se wapas bhej deta
  hai. Fetch karne wale ka app jawab padhte hi Google Maps apne aap khol deta hai.
  Safety: member ka phone sirf family ke registered numbers ki request par aur
  tabhi jawab deta hai jab uski sharing ON ho (ek number ko 2 minute me ek baar).
  Fetch karne wala sirf wahi jawab maanta hai jiski request usne 15 minute ke
  andar khud bheji ho. Dono phones me SIM + network signal chahiye.
  Dual SIM phone me app har SMS **dono SIM se** bhejta hai (jis SIM me balance
  hai wahi se SMS jata hai), sirf default SIM se nahi.
- **Switch off / Airplane mode se pehle SMS:** jab phone band (switch off ya
  restart) ho raha ho, ya Airplane mode ON ho, to app family ke numbers ko
  apni aakhri GPS location SMS se bhej deta hai (sharing ON ho tabhi, dono SIM
  se). Ye native Android code hai (`packages/sim_subs`, `PowerEventSms.java`)
  jo `ACTION_SHUTDOWN` / `AIRPLANE_MODE_CHANGED` sunta hai. Limits: Android
  band hone se *pehle* ka koi "will shutdown" signal nahi deta, sirf shutdown
  shuru hone par kuch second milte hain; Airplane mode me radio turant band
  ho sakta hai, isliye wahan SMS jana pakka nahi. Location app ke aakhri saved
  GPS fix se aati hai (max ~1 minute purani), naya fix lene ka waqt nahi hota.
- Notification hamesha dikhta hai jab tak sharing ON hai (Android ka rule hai,
  taaki jis insaan ki location ja rahi hai use hamesha pata rahe).

## APK kaise milega

GitHub Actions har push par APK banata hai aur **Releases** me daal deta hai:
Repo page > **Releases** > latest version > `app-release.apk` download karke install karo.
(Pehli baar "Install unknown apps" allow karna padega.)

## Firebase me ek baar ye karna zaroori hai

1. **Authentication > Sign-in method > Anonymous** ko Enable karo.
2. **Firestore Database** banao (location `asia-south1`).
3. Firestore **Rules** me `firestore.rules` ka content paste karke Publish karo
   (isme `members` aur `numbers`, dono collections ke rules hain).

## Use kaise kare

1. App kholo, apna number (ya do number) daalo, "Location share shuru karo" dabao.
   Permission maange to sab Allow karo ("Allow all the time", SMS, aur
   "Display over other apps" bhi — ye SMS se location aane par Google Maps
   apne aap kholne ke liye hai). Kuch phones (Xiaomi/Oppo/Vivo) me Settings >
   Apps > is app > "Display pop-up windows while running in background" bhi
   ON karna padta hai.
2. Dusre ki location dekhni ho: naam (optional) aur unka number daalo, "Location
   fetch karo" dabao.
3. Apna number badalna ho: hamburger menu (top-left) > "Mera number edit karo".
4. App bar ke SMS icon se dekh sakte ho ki abhi SMS fallback me kaunse
   numbers hain (apne aap aate hain) — "Refresh" se turant update bhi kar
   sakte ho.

## Data model (Firestore)

- `families/{code}/members/{memberId}` — ek member ki taaza location.
  `memberId` phone se alag, sthayi hota hai (phone me hi save).
- `families/{code}/numbers/{phone}` — ek chhota pointer jo phone number ko
  uske `memberId` se jodta hai. Number edit karne par purana pointer kabhi
  mitaya nahi jaata, isliye purana number bhi hamesha fetch ho sakta hai.

## Note

- Family code ek tarah ka password hai, isliye lamba aur secret rakho.
- `config/google-services.json` Firebase ki public client config hai. Asli security `firestore.rules` se aati hai.
- `android/` folder build ke waqt apne aap banta hai (`scripts/patch_android.py` permissions, app label aur Firebase plugin jodta hai).
