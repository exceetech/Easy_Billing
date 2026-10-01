import re

with open("app/src/main/java/com/example/easy_billing/InvoiceActivity.kt", "r") as f:
    content = f.read()

# 1. Properties
old_props = """    private lateinit var rowIgst: View
    private lateinit var tvCgstAmount: TextView
    private lateinit var tvSgstAmount: TextView
    private lateinit var tvIgstAmount: TextView
    private lateinit var tvCgstLabel: TextView
    private lateinit var tvSgstLabel: TextView
    private lateinit var tvIgstLabel: TextView
    private lateinit var tvCgstRate: TextView
    private lateinit var tvSgstRate: TextView
    private lateinit var tvIgstRate: TextView"""
new_props = """    private lateinit var rowIgst: View
    private lateinit var rowCess: View
    private lateinit var tvCgstAmount: TextView
    private lateinit var tvSgstAmount: TextView
    private lateinit var tvIgstAmount: TextView
    private lateinit var tvCessAmount: TextView
    private lateinit var tvCgstLabel: TextView
    private lateinit var tvSgstLabel: TextView
    private lateinit var tvIgstLabel: TextView
    private lateinit var tvCessLabel: TextView
    private lateinit var tvCgstRate: TextView
    private lateinit var tvSgstRate: TextView
    private lateinit var tvIgstRate: TextView
    private lateinit var tvCessRate: TextView"""
content = content.replace(old_props, new_props)

# 2. findViewById
old_finds = """        rowIgst           = findViewById(R.id.rowIgst)
        tvCgstAmount      = findViewById(R.id.tvCgstAmount)
        tvSgstAmount      = findViewById(R.id.tvSgstAmount)
        tvIgstAmount      = findViewById(R.id.tvIgstAmount)
        tvCgstLabel       = findViewById(R.id.tvCgstLabel)
        tvSgstLabel       = findViewById(R.id.tvSgstLabel)
        tvIgstLabel       = findViewById(R.id.tvIgstLabel)
        tvCgstRate        = findViewById(R.id.tvCgstRate)
        tvSgstRate        = findViewById(R.id.tvSgstRate)
        tvIgstRate        = findViewById(R.id.tvIgstRate)"""
new_finds = """        rowIgst           = findViewById(R.id.rowIgst)
        rowCess           = findViewById(R.id.rowCess)
        tvCgstAmount      = findViewById(R.id.tvCgstAmount)
        tvSgstAmount      = findViewById(R.id.tvSgstAmount)
        tvIgstAmount      = findViewById(R.id.tvIgstAmount)
        tvCessAmount      = findViewById(R.id.tvCessAmount)
        tvCgstLabel       = findViewById(R.id.tvCgstLabel)
        tvSgstLabel       = findViewById(R.id.tvSgstLabel)
        tvIgstLabel       = findViewById(R.id.tvIgstLabel)
        tvCessLabel       = findViewById(R.id.tvCessLabel)
        tvCgstRate        = findViewById(R.id.tvCgstRate)
        tvSgstRate        = findViewById(R.id.tvSgstRate)
        tvIgstRate        = findViewById(R.id.tvIgstRate)
        tvCessRate        = findViewById(R.id.tvCessRate)"""
content = content.replace(old_finds, new_finds)

# 3. bindTaxTile
old_binds = """        bindTaxTile(rowCgst, tvCgstLabel, tvCgstAmount, tvCgstRate, cgstOn, breakdown.totalCgst, breakdown.taxableValue)
        bindTaxTile(rowSgst, tvSgstLabel, tvSgstAmount, tvSgstRate, sgstOn, breakdown.totalSgst, breakdown.taxableValue)
        bindTaxTile(rowIgst, tvIgstLabel, tvIgstAmount, tvIgstRate, igstOn, breakdown.totalIgst, breakdown.taxableValue)"""
new_binds = """        bindTaxTile(rowCgst, tvCgstLabel, tvCgstAmount, tvCgstRate, cgstOn, breakdown.totalCgst, breakdown.taxableValue)
        bindTaxTile(rowSgst, tvSgstLabel, tvSgstAmount, tvSgstRate, sgstOn, breakdown.totalSgst, breakdown.taxableValue)
        bindTaxTile(rowIgst, tvIgstLabel, tvIgstAmount, tvIgstRate, igstOn, breakdown.totalIgst, breakdown.taxableValue)
        val cessOn = breakdown.totalCess > 0.0
        bindTaxTile(rowCess, tvCessLabel, tvCessAmount, tvCessRate, cessOn, breakdown.totalCess, breakdown.taxableValue)"""
content = content.replace(old_binds, new_binds)

with open("app/src/main/java/com/example/easy_billing/InvoiceActivity.kt", "w") as f:
    f.write(content)
