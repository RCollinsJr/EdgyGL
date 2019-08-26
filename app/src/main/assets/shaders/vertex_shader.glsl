#version 300 es

// The matrix the camera internally applies to the output it produces
uniform mat4 cameraTextureMatrix;

// MVP matrix for the quad we are drawing
uniform mat4 mvpMatrix;

in vec4 position;
in vec4 texturePosition;

out vec2 cameraTextureCoordinate;

void main() {
    cameraTextureCoordinate = (cameraTextureMatrix * texturePosition).xy;
    gl_Position = mvpMatrix * position;
}