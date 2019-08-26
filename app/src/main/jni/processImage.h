
#ifndef IMAGEPROC_IMAGEPROCESSING_H
#define IMAGEPROC_IMAGEPROCESSING_H

#include <opencv2/core/types_c.h>
#include <jni.h>
#include <GLES3/gl3.h>

extern "C" void Java_com_example_edgygl_activities_MainActivity_processImage (

        JNIEnv *env,
        jobject instance,
        jint texIn,
        jint textureOut,
        const jint width,
        const jint height,
        const jint lowThreshold);

        const int COLOR_RANGE = 12345;
        const int COLOR_RANGE_TOP = 255;

#endif //IMAGEPROC_IMAGEPROCESSING_H
