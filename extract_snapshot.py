import re
import html
import json

file_path = r"C:\Users\john\.gemini\antigravity\scratch\Senpaistream-scraper\exemple page film.html"

with open(file_path, 'r', encoding='utf-8') as f:
    content = f.read()

# Find wire:snapshot attribute value
# It matches wire:snapshot="value"
match = re.search(r'wire:snapshot="([^"]+)"', content)

if match:
    encoded_json = match.group(1)
    # Decode HTML entities
    decoded_json = html.unescape(encoded_json)
    
    try:
        data = json.loads(decoded_json)
        print("Successfully parsed JSON")
        
        # Save prettified JSON
        with open('snapshot.json', 'w', encoding='utf-8') as f:
            json.dump(data, f, indent=2)
            
        # Also print videos section specifically
        if 'data' in data and 'videos' in data['data']:
            print("Found 'videos' in snapshot data")
        else:
            print("'videos' not found in snapshot data")
            
    except json.JSONDecodeError as e:
        print(f"JSON Decode Error: {e}")
        with open('snapshot_raw.txt', 'w', encoding='utf-8') as f:
            f.write(decoded_json)
else:
    print("Could not find wire:snapshot attribute")
