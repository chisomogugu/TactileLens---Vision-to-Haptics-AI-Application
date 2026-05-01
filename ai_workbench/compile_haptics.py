import os
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

def compile_haptics():
    print("\n🚀 Starting AI Hub compilation for Haptics Pipeline (EfficientNet + Linear Head)...")
    setup_api_token()
    
    device = hub.Device("Samsung Galaxy S25 Ultra") 
    
    # Paths to the raw ONNX models provided by the ML team
    enc_onnx = "exports/efficientnet_lite0.onnx"
    head_onnx = "exports/linear_head.onnx"

    # =========================================================================
    # 1. Compile EfficientNet-Lite0 Encoder
    # =========================================================================
    print(f"\n[1/2] Submitting EfficientNet-Lite0 to Qualcomm AI Hub for {device.name}...")
    enc_job = hub.submit_compile_job(
        model=enc_onnx,
        device=device,
        input_specs={"image": (1, 3, 224, 224)},
        options="--target_runtime tflite", 
        name="tactilelens_efficientnet_lite0"
    )
    
    print(f"Waiting for Encoder compilation to finish (Job ID: {enc_job.job_id})...")
    enc_model = enc_job.get_target_model()
    print("✅ Encoder Compilation complete!")
    
    # Download Encoder
    output_dir = "app/src/main/assets"
    os.makedirs(output_dir, exist_ok=True)
    enc_out = os.path.join(output_dir, "efficientnet_lite0.tflite")
    if os.path.exists(enc_out):
        os.remove(enc_out)
    enc_model.download(enc_out)
    print(f"⬇️ Saved optimized Encoder to: {enc_out}")

    # =========================================================================
    # 2. Compile Linear Head
    # =========================================================================
    print(f"\n[2/2] Submitting Linear Head to Qualcomm AI Hub for {device.name}...")
    head_job = hub.submit_compile_job(
        model=head_onnx,
        device=device,
        input_specs={"features": (1, 1280)},
        options="--target_runtime tflite", 
        name="tactilelens_linear_head"
    )
    
    print(f"Waiting for Linear Head compilation to finish (Job ID: {head_job.job_id})...")
    head_model = head_job.get_target_model()
    print("✅ Linear Head Compilation complete!")
    
    # Download Head
    head_out = os.path.join(output_dir, "linear_head.tflite")
    if os.path.exists(head_out):
        os.remove(head_out)
    head_model.download(head_out)
    print(f"⬇️ Saved optimized Linear Head to: {head_out}")

    print("\n🎉 Done! The natively compiled Haptics pipeline is now integrated into Android Studio.")

if __name__ == "__main__":
    compile_haptics()
