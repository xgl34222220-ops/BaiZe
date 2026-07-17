from pathlib import Path

path = Path("v2/app/src/main/java/io/github/xgl34222220/baize/DashboardActivity.kt")
text = path.read_text()
old = 'binding.scanOnlyButton.setOnClickListener { runModuleTask("scan") }'
new = 'binding.scanOnlyButton.setOnClickListener { startActivity(Intent(this, SmartScanActivity::class.java)) }'
if new in text:
    print("Dashboard scan action already uses SmartScanActivity")
elif old in text:
    path.write_text(text.replace(old, new, 1))
    print("Dashboard scan action now opens the direct smart-scan snapshot flow")
else:
    raise SystemExit("Dashboard scan action pattern not found")
