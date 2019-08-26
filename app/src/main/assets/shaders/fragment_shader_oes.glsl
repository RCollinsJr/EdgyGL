#version 300 es
#extension GL_OES_EGL_image_external : enable
#extension GL_OES_EGL_image_external_essl3 : enable

precision mediump float;

uniform samplerExternalOES cameraTexture;

in vec2 cameraTextureCoordinate;
out vec4 fragmentColor;

void main () {
    vec4 color = texture(cameraTexture, cameraTextureCoordinate);
    fragmentColor = color;
}
