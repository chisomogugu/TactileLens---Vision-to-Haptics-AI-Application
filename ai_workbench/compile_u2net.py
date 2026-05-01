import os
import requests
import qai_hub as hub

def setup_api_token():
    """Reads the AI Hub API token from the .env file."""
    env_path = ".env"
    if os.path.exists(env_path):
        with open(env_path, "r") as f:
            for line in f:
                if "QUALCOMM_AIHub_API_KEY" in line:
                    token = line.split("=")[1].strip().strip('"').strip("'")
                    os.environ["QAI_HUB_API_TOKEN"] = token
                    print("✅ Qualcomm AI Hub API Token loaded from .env")
                    return
    print("⚠️ Warning: Could not find QUALCOMM_AIHub_API_KEY in .env")

def download_onnx_model():
    """Downloads the U2Net ONNX model natively as seen in the jupyter notebook."""
    onnx_url = "https://github.com/danielgatis/rembg/releases/download/v0.0.0/u2netp.onnx"
    onnx_path = "U2NetModel/u2netp.onnx"
    
    if not os.path.exists(onnx_path):
        print(f"Downloading u2netp.onnx from {onnx_url}...")
        r = requests.get(onnx_url, stream=True)
        r.raise_for_status()
        with open(onnx_path, "wb") as f:
            for chunk in r.iter_content(chunk_size=8192):
                f.write(chunk)
        print(f"✅ Download complete: {os.path.getsize(onnx_path)/1e6:.1f} MB")
    else:
        print("✅ ONNX model already exists locally.")
    
    return onnx_path

def compile_u2net():
    print("\n🚀 Starting AI Hub compilation for U2Net Segmentation via ONNX...")
    setup_api_token()
    
    device = hub.Device("Samsung Galaxy S25 Ultra") 
    
    # 1. Download the raw ONNX graph
    source_model_path = download_onnx_model()

    print(f"Submitting U2Net ONNX to Qualcomm AI Hub for {device.name}...")
    
    # 2. Submit ONNX to AI Hub
    # Notebook specifies the input name is 'input.1' and shape is [1, 3, 320, 320]
    compile_job = hub.submit_compile_job(
        model=source_model_path,
        device=device,
        input_specs={"input.1": (1, 3, 320, 320)},
        options="--target_runtime tflite", 
        name="tactilelens_u2net_segmentation"
    )
    
    print(f"Waiting for compilation to finish (Job ID: {compile_job.job_id})...")
    target_model = compile_job.get_target_model()
    print("✅ Compilation complete!")
    
    # 3. Profile Latency
    print("Submitting profiling job to check latency...")
    profile_job = hub.submit_profile_job(
        model=target_model,
        device=device,
        name="tactilelens_u2net_profiling"
    )
    
    print("Waiting for profiling to finish...")
    # Wrap in try/catch in case the AI hub API changed or the property doesn't exist
    try:
        if hasattr(profile_job, 'get_target_model_profile'):
            profile_data = profile_job.get_target_model_profile()
            estimated_latency = profile_data.execution_time_ms
        else:
            profile_data = profile_job.get_profile()
            estimated_latency = profile_data.execution_time_ms if hasattr(profile_data, 'execution_time_ms') else 0
        print(f"✅ Profiling complete. Estimated U2Net Latency: {estimated_latency:.2f} ms")
        
        if estimated_latency > 15.0:
            print("⚠️ WARNING: U2Net latency is high. You only have 20ms total for the whole haptic loop!")
    except Exception as e:
        print(f"⚠️ Could not fetch exact latency data: {e}. Proceeding to download model...")
    
    # 4. Download optimized TFLite binary to Android Assets
    output_dir = "app/src/main/assets"
    os.makedirs(output_dir, exist_ok=True)
    output_path = os.path.join(output_dir, "u2net_optimized.tflite")
    
    # If we previously bypassed and copied the model, remove it so we don't conflict
    if os.path.exists(output_path):
        os.remove(output_path)
        
    print(f"Downloading Hexagon-optimized LiteRT model to Android Studio: {output_path}")
    target_model.download(output_path)
    print("🎉 Done! The natively compiled U2Net is now integrated into Android Studio.")

if __name__ == "__main__":
    compile_u2net()
