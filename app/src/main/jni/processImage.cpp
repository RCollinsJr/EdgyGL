
#include <jni.h>
#include <opencv2/core/core.hpp>
#include <opencv2/imgproc/imgproc.hpp>
#include "processImage.h"

using namespace std;
using namespace cv;

/**
 * A function using the OpenCV computer vision library that
 * does a simple edge detection of the input image
 *
 * @param env
 * @param instance
 * @param textureIn
 * @param textureOut
 * @param width
 * @param height
 * @param lowThreshold
 *
 */
extern "C" void Java_com_example_edgygl_activities_MainActivity_processImage(

        JNIEnv *env,
        jobject instance,
        jint textureIn,
        jint textureOut,
        const jint width,
        const jint height,
        const jint lowThreshold) {

    /// ratio of Low canny threshold to High canny threshold
    const int THRESHOLD_RATIO = 4;

    /// adjust for best combination of speed and performance
    const int SCALE_FACTOR = 4;

    RNG rng(COLOR_RANGE);

    /// timing variables (not used at this time) (pun)
    auto startTime = (double) getTickCount();
    auto endTime = (double) getTickCount();

    /// create a mat the size of the preview and read pixels from GL texture into the mat
    static UMat inputMat;
    inputMat.create(height, width, CV_8UC4);
    glReadPixels(0, 0, inputMat.cols, inputMat.rows, GL_RGBA, GL_UNSIGNED_BYTE, inputMat.getMat(ACCESS_WRITE).data);

    /// re-size the mat to a smaller dimension to keep processing time to a reasonable level
    Mat reSized;
    cv::resize(inputMat, reSized, cv::Size(inputMat.cols / SCALE_FACTOR, inputMat.rows / SCALE_FACTOR));

    /// convert the image to grayscale
    UMat gray_image;
    cvtColor(reSized, gray_image, COLOR_BGRA2GRAY);

    /// Reduce noise with a 3x3 kernel
    Mat blurred;
    GaussianBlur(gray_image, blurred, Size(3, 3), 0);

    /// create new cv::Mat, canny it and convert
    Mat cannyMat(height, width, CV_8UC1);
    Canny(blurred, cannyMat, lowThreshold, lowThreshold * THRESHOLD_RATIO, 3);

    /// Find contours
    vector<vector<Point> > contours;
    vector<Vec4i> hierarchy;
    findContours(cannyMat, contours, hierarchy, RETR_TREE, CHAIN_APPROX_SIMPLE, Point(0, 0));

    /// Draw contours
    Mat drawing = Mat::zeros( cannyMat.size(), CV_8UC4 );
    for( int i = 0; i< contours.size(); i++ )
    {
        // get a random color
        Scalar color = Scalar(
                rng.uniform(0, COLOR_RANGE_TOP),
                rng.uniform(0, COLOR_RANGE_TOP),
                rng.uniform(0, COLOR_RANGE_TOP) );

        // draw the contour using the random color
        drawContours(drawing, contours, i, color, 1, LINE_8, hierarchy, 0, Point());
    }

    /// resize the image back to it's original dimensions
    cv::resize(drawing, reSized, cv::Size(inputMat.cols, inputMat.rows), INTER_LINEAR);

    /// add the two layers together into the collimation mat
    addWeighted(reSized, 1.00, inputMat, 1.00, 0.0, inputMat);

    /// set the active texture
    glActiveTexture(GL_TEXTURE0);

    /// bind to the active texture
    glBindTexture(GL_TEXTURE_2D, static_cast<GLuint>(textureOut));

    /// specify a two-dimensional texture sub-image
    glTexSubImage2D(
            GL_TEXTURE_2D,
            0,
            0,
            0,
            inputMat.cols,
            inputMat.rows,
            GL_RGBA,
            GL_UNSIGNED_BYTE,
            inputMat.getMat(ACCESS_READ).data
    );
}

