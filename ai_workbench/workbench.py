import os
import qai_hub as hub
import torch
from torchvision.models import mobilenet_v2

def setup_api_token():
    """
    Reads the AI Hub API token from the .env file and sets the environment variable.
    """
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

def compile_and_export_model(
    model: torch.nn.Module, 
    input_shape: tuple = (1, 3, 224, 224),
    model_name: str = "tactilelens_texture_model"
):
    """
    Compiles the PyTorch model for the Snapdragon Hexagon NPU,
    profiles it, and exports it directly to the Android Studio project assets.
    
    Args:
        model: The custom trained PyTorch model for texture classification/feature extraction.
        input_shape: The expected input shape for the model.
        model_name: The name of the model to be displayed in the AI Hub.
    """
    print(f"\n🚀 Starting compilation workflow for {model_name}...")
    setup_api_token()
    
    # 1. Define the target device
    # Target device is the Samsung Galaxy S25 Ultra (Snapdragon 8 Gen 4 / Hexagon NPU)
    device = hub.Device("Samsung Galaxy S25 Ultra") 
    
    # 2. Trace the PyTorch model
    print("Tracing the PyTorch model...")
    example_input = torch.rand(input_shape)
    traced_model = torch.jit.trace(model, example_input)
    
    # 3. Submit Compile Job targeting LiteRT (.tflite)
    print(f"Submitting compilation job to Qualcomm AI Hub for {device.name}...")
    compile_job = hub.submit_compile_job(
        model=traced_model,
        device=device,
        input_specs=dict(image=input_shape),
        options="--target_runtime tflite", # Compile to LiteRT format
        name=model_name
    )
    
    print(f"Waiting for compilation to finish (Job ID: {compile_job.job_id})...")
    target_model = compile_job.get_target_model()
    print("✅ Compilation complete!")
    
    # 4. Submit Profile Job to verify sub-20ms latency
    print("Submitting profiling job to ensure sub-20ms latency...")
    profile_job = hub.submit_profile_job(
        model=target_model,
        device=device,
        name=f"{model_name}_profiling"
    )
    
    print("Waiting for profiling to finish...")
    profile_data = profile_job.get_target_model_profile()
    estimated_latency = profile_data.execution_time_ms
    print(f"✅ Profiling complete. Estimated Latency: {estimated_latency:.2f} ms")
    
    if estimated_latency > 20.0:
        print("⚠️ WARNING: Estimated latency is above 20ms. This may disrupt the haptic feedback experience.")
    
    # 5. Export directly to Android Studio assets
    output_dir = "app/src/main/assets"
    os.makedirs(output_dir, exist_ok=True)
    output_path = os.path.join(output_dir, "texture_model.tflite")
    
    print(f"Downloading compiled LiteRT model to Android Studio project: {output_path}")
    target_model.download(output_path)
    print("🎉 Done! The model is now connected to Android Studio and ready for inference.")

# =====================================================================
# For the ML Engineer: 
# When your custom model is ready, uncomment and run the following code:
# =====================================================================
if __name__ == "__main__":
    pass
    # from your_model_file import CustomTextureModel
    #
    # # Initialize your custom model
    # model = CustomTextureModel()
    # model.eval() # Ensure it's in evaluation mode
    #
    # # Call the connection function
    # # Ensure the input_shape matches your model's expected input 
    # compile_and_export_model(
    #     model=model, 
    #     input_shape=(1, 3, 224, 224), # e.g. Batch=1, Channels=3, Height=224, Width=224
    #     model_name="tactilelens_texture_v1"
    # )
