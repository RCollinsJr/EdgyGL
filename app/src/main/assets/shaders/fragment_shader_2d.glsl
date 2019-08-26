#version 300 es

precision mediump float;

uniform sampler2D sampler2DTexture;

in vec2 cameraTextureCoordinate;
out vec4 fragmentColor;

void main () {
    vec4 color = texture(sampler2DTexture, cameraTextureCoordinate);
    fragmentColor = color;
}

