#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
TXT 轉 JSON 工具
將合庫常見QA.txt 轉換為 faq.json 格式
"""

import json
import re
import sys
import os

def convert_txt_to_json(input_file, output_file):
    """
    將 QA TXT 檔案轉換為 JSON 格式
    
    Args:
        input_file: 輸入的 TXT 檔案路徑
        output_file: 輸出的 JSON 檔案路徑
    """
    qa_list = []
    
    try:
        with open(input_file, "r", encoding="utf-8") as f:
            content = f.read()
    except FileNotFoundError:
        print(f"錯誤：找不到檔案 {input_file}")
        sys.exit(1)
    
    # 使用正規表達式匹配 Q 和 A
    # 格式：Q1：問題\nA1：答案
    pattern = r'Q(\d+)[：:]\s*(.+?)\r?\n\s*A\d+[：:]\s*(.+?)(?=\r?\n\r?\nQ\d+[：:]|\r?\n\r?\n\r?\n|\Z)'
    
    matches = re.findall(pattern, content, re.DOTALL)
    
    for match in matches:
        qa_id = int(match[0])
        question = match[1].strip()
        answer = match[2].strip()
        
        # 清理答案中的多餘換行
        answer = re.sub(r'\r?\n\s*', '', answer)
        
        qa_list.append({
            "id": qa_id,
            "question": question,
            "answer": answer
        })
    
    # 按 ID 排序
    qa_list.sort(key=lambda x: x['id'])
    
    # 寫入 JSON 檔案
    with open(output_file, "w", encoding="utf-8") as f:
        json.dump(qa_list, f, ensure_ascii=False, indent=2)
    
    print(f"✅ 成功轉換 {len(qa_list)} 條 QA 到 {output_file}")
    return qa_list

def main():
    # 預設路徑
    script_dir = os.path.dirname(os.path.abspath(__file__))
    parent_dir = os.path.dirname(script_dir)
    
    input_file = os.path.join(parent_dir, "合庫常見QA.txt")
    output_file = os.path.join(script_dir, "src", "main", "resources", "faq.json")
    
    # 如果提供了命令列參數
    if len(sys.argv) >= 3:
        input_file = sys.argv[1]
        output_file = sys.argv[2]
    elif len(sys.argv) == 2:
        input_file = sys.argv[1]
    
    print(f"📖 讀取檔案: {input_file}")
    print(f"📝 輸出檔案: {output_file}")
    
    qa_list = convert_txt_to_json(input_file, output_file)
    
    # 顯示前 3 條作為預覽
    print("\n📋 預覽前 3 條 QA:")
    for qa in qa_list[:3]:
        print(f"  [{qa['id']}] Q: {qa['question'][:40]}...")
        print(f"       A: {qa['answer'][:50]}...")
        print()

if __name__ == "__main__":
    main()
