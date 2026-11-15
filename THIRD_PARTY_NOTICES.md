# Third-Party Notices — VideoX AI Upscaler (v2.15)

VideoX itself is Avesh Khan's app. The AI upscaler feature uses the
following third-party components. Their licences are reproduced in
summary here; the full licence texts live in the upstream repositories.

## Real-ESRGAN (AI upscaling model)

- Project: https://github.com/xinntao/Real-ESRGAN
- Model used: realesr-general-x4v3 (ONNX export of the official weights)
- Licence: BSD 3-Clause License, Copyright (c) 2021, Xintao Wang.
- The model weights are distributed as release assets of the same
  repository, which carries no separate licence statement for the
  weights. VideoX proceeds on the same basis as the wider ecosystem
  (Upscayl and others): the BSD 3-Clause licence covers the project
  including its released weights, with attribution. The weights are
  downloaded at runtime from a mirror of the official release and
  verified by SHA-256; they are not bundled with the app.
- BSD 3-Clause conditions: redistributions must retain the copyright
  notice, the list of conditions and the disclaimer; the author's name
  must not be used to endorse derived products without permission.

## ONNX Runtime (on-device inference)

- Project: https://github.com/microsoft/onnxruntime
- Artifact: com.microsoft.onnxruntime:onnxruntime-android:1.29.0
- Licence: MIT License, Copyright (c) Microsoft Corporation.
- Permission is granted, free of charge, to use, copy, modify, merge,
  publish, distribute, sublicense and sell copies of the software,
  subject to including the copyright and permission notices.

## FFmpeg (video decode/encode)

- FFmpeg is bundled through the youtubedl-android ffmpeg module that
  VideoX already uses for downloads (since v2.0). It is used here only
  to decode video frames and encode the finished file. No FFmpeg code
  was modified.
