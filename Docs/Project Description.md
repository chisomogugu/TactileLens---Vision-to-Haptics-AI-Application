TactileLens -  Vision to Audio and Haptic AI Application

Summary: TactileLens is an on-device AI application that translates visual textures into real-time haptic feedback, so users can “feel” the world through their smartphones.Also we would include audio to add to the users experience and sense of touch. By pointing the Samsung Galaxy S25 Ultra at a surface, the AI app analyzes visual microtextures (such as the grit of gravel or the smoothness of silk) and translates them into complex, real-time haptic feedback.

Use Cases 
Accessibility: Low-vision users can perceive surface qualities and environmental hazards (gravel, water, instability) safely through touch.
Global Telepresence: Users can “feel” textures from across the world. Whether it’s a shopper checking the quality of a fabric in a remote boutique or a traveler “touching” a historical monument via a photo.

Our Vision:
We will use LiteRT to deploy a high-performance texture classification and feature-extraction model, optimized via the Qualcomm AI Hub, directly onto the Snapdragon Hexagon NPU. The model outputs a continuous feature vector representing physical attributes such as roughness, granularity, hardness, and friction. These features are then mapped to both Android’s haptic system and a low-latency audio engine, where each attribute contributes to a distinct and consistent “sensory signature.” For example, higher roughness may produce rapid, irregular vibration pulses accompanied by high-frequency "gritty" audio transients, while smoother surfaces generate continuous, low-frequency haptic and acoustic feedback.To maintain the “illusion of touch,” latency for both haptics and audio must be sub-20ms. We will offload the pixel-shuffling and feature-vector math to the NPU. This allows the CPU to remain dedicated to managing the Android Haptics and Audio APIs, ensuring smooth, low-jitter feedback.




