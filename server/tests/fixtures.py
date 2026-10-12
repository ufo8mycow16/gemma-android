"""Shared test data."""

# A Qwen-Image style ComfyUI workflow in API format, as exported from ComfyUI ("Save (API)").
WORKFLOW = {
    "3": {"class_type": "KSampler", "inputs": {"seed": 1, "steps": 20, "cfg": 2.5, "positive": ["6", 0], "negative": ["7", 0],
                                                "latent_image": ["5", 0], "model": ["4", 0]}},
    "4": {"class_type": "UNETLoader", "inputs": {"unet_name": "qwen_image_fp8.safetensors"}},
    "5": {"class_type": "EmptySD3LatentImage", "inputs": {"width": 1024, "height": 1024, "batch_size": 1}},
    "6": {"class_type": "CLIPTextEncode", "inputs": {"text": "placeholder", "clip": ["8", 0]}},
    "7": {"class_type": "CLIPTextEncode", "inputs": {"text": "", "clip": ["8", 0]}},
    "8": {"class_type": "CLIPLoader", "inputs": {"clip_name": "qwen_2.5_vl.safetensors"}},
    "10": {"class_type": "VAEDecode", "inputs": {"samples": ["3", 0]}},
    "9": {"class_type": "SaveImage", "inputs": {"images": ["10", 0], "filename_prefix": "gemma"}},
}
