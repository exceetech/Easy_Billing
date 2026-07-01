import os

files = [
    '/Users/adeebfarhan/Desktop/expos/Easy_Billing/app/src/main/java/com/example/easy_billing/DashboardActivity.kt',
    '/Users/adeebfarhan/Desktop/expos/Easy_Billing/app/src/main/java/com/example/easy_billing/sync/SyncCoordinator.kt',
    '/Users/adeebfarhan/Desktop/expos/Easy_Billing/app/src/main/java/com/example/easy_billing/util/NetworkReceiver.kt'
]

insert_str = """
                syncManager.pullPurchaseBatches()
                syncManager.pullPurchaseReturns()
                syncManager.pullCreditNotes()"""

for fpath in files:
    if os.path.exists(fpath):
        with open(fpath, 'r') as f:
            content = f.read()
        
        # Check if already added
        if "pullPurchaseBatches" not in content:
            # We want to insert it right before `syncManager.pullInventory()`
            content = content.replace("syncManager.pullInventory()", f"{insert_str}\n                syncManager.pullInventory()")
            
            with open(fpath, 'w') as f:
                f.write(content)
            print(f"Updated {fpath}")
