# 臨時檔案管理
import os

async def save_upload_file(file) ->str:
    """
    保存上傳的檔案到臨時位置
    
    Returns:
        臨時檔案路徑
    """
    temp_path = f"temp_{file.filename}"
    content = await file.read()

    with open(temp_path , "wb") as f:
        f.write(content)
    
    return temp_path

def cleanup_file(file_path: str) -> None:
    """刪除臨時檔案"""
    if os.path.exists(file_path):
        os.remove(file_path)