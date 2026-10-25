import os
import sys

# [1] ดักจับกรณีผู้ใช้เผลอรันผิด Environment โดยไม่มีไลบรารี requests
try:
    import requests
    from openai import OpenAI
except ModuleNotFoundError as e:
    print("❌ [Import Error] ไม่พบมอดูลที่จำเป็นในการรันระบบ!")
    print("👉 กรุณารันคำสั่ง: source venv/bin/activate && pip install requests openai")
    sys.exit(1)

def run_auto_router():
    """
    [Docstring] ฟังก์ชันตรวจสอบและดึง API Key ของ มข. มาประมวลผล
    """
    api_key = os.environ.get("KKU_API_KEY")
    if not api_key:
        print("⚠️ [Warning] ไม่พบ KKU_API_KEY ในระบบ กรุณาเช็กไฟล์ .zshrc")
        return

    print("🚀 ระบบ Auto Router พร้อมทำงานบน Virtual Environment ของ Mac แล้ว!")

if __name__ == "__main__":
    run_auto_router()