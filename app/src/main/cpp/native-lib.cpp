#include <jni.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <queue>
#include <vector>

namespace {

constexpr float kPi = 3.14159265358979323846f;

struct Candidate {
    int minX;
    int minY;
    int maxX;
    int maxY;
    int area;
    int perimeter;
    float fillRatio;
    float averageLuma;
    float averageU;
    float averageV;
    float contrast;
    float colorScore;
    float score;
};

enum BallProfile {
    PROFILE_AUTO = 0,
    PROFILE_ORANGE = 1,
    PROFILE_WHITE = 2,
};

inline uint8_t readY(
    const jbyte* plane,
    int x,
    int y,
    int rowStride
) {
    return static_cast<uint8_t>(plane[y * rowStride + x]);
}

inline uint8_t readUV(
    const jbyte* plane,
    int x,
    int y,
    int rowStride,
    int pixelStride
) {
    return static_cast<uint8_t>(plane[y * rowStride + x * pixelStride]);
}

bool isOrangeBallColor(int luma, int u, int v) {
    return luma > 82 && v > 145 && (v - u) > 18;
}

bool isWhiteBallColor(int luma, int u, int v) {
    return luma > 175 && std::abs(u - 128) < 18 && std::abs(v - 128) < 18;
}

bool matchesBallProfile(int luma, int u, int v, int ballProfile) {
    if (ballProfile == PROFILE_ORANGE) {
        return isOrangeBallColor(luma, u, v);
    }
    if (ballProfile == PROFILE_WHITE) {
        return isWhiteBallColor(luma, u, v);
    }
    return isOrangeBallColor(luma, u, v) || isWhiteBallColor(luma, u, v);
}

float computeColorScore(float averageLuma, float averageU, float averageV, int ballProfile) {
    const float orangeTargetU = 92.0f;
    const float orangeTargetV = 182.0f;
    const float orangeDistance =
        std::sqrt(
            (averageU - orangeTargetU) * (averageU - orangeTargetU) +
            (averageV - orangeTargetV) * (averageV - orangeTargetV)
        );
    const float orangeDistanceScore = std::clamp(1.0f - (orangeDistance / 72.0f), 0.0f, 1.0f);
    const float orangeSeparation = std::clamp(((averageV - averageU) - 12.0f) / 70.0f, 0.0f, 1.0f);
    const float orangeBrightness = std::clamp((averageLuma - 75.0f) / 95.0f, 0.0f, 1.0f);
    const float orangeScore = orangeDistanceScore * 0.45f + orangeSeparation * 0.35f + orangeBrightness * 0.20f;
    const float whiteChromaticDistance =
        (std::abs(averageU - 128.0f) + std::abs(averageV - 128.0f)) / 2.0f;
    const float whiteNeutrality = std::clamp(1.0f - (whiteChromaticDistance / 20.0f), 0.0f, 1.0f);
    const float whiteBrightness = std::clamp((averageLuma - 170.0f) / 55.0f, 0.0f, 1.0f);
    const float whiteScore = whiteNeutrality * 0.65f + whiteBrightness * 0.35f;

    if (ballProfile == PROFILE_ORANGE) {
        return orangeScore;
    }
    if (ballProfile == PROFILE_WHITE) {
        return whiteScore;
    }
    return std::max(orangeScore, whiteScore);
}

Candidate findBestCandidate(
    const std::vector<uint8_t>& mask,
    const std::vector<uint8_t>& lumaMap,
    const std::vector<uint8_t>& uMap,
    const std::vector<uint8_t>& vMap,
    int maskWidth,
    int maskHeight,
    int ballProfile
) {
    std::vector<uint8_t> visited(maskWidth * maskHeight, 0);
    Candidate best{0, 0, 0, 0, 0, 0, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f};
    const std::array<int, 4> dx{1, -1, 0, 0};
    const std::array<int, 4> dy{0, 0, 1, -1};

    for (int y = 0; y < maskHeight; ++y) {
        for (int x = 0; x < maskWidth; ++x) {
            const int index = y * maskWidth + x;
            if (mask[index] == 0 || visited[index] != 0) {
                continue;
            }

            std::queue<int> queue;
            queue.push(index);
            visited[index] = 1;

            int minX = x;
            int maxX = x;
            int minY = y;
            int maxY = y;
            int area = 0;
            int lumaSum = 0;
            int uSum = 0;
            int vSum = 0;
            int perimeter = 0;

            while (!queue.empty()) {
                const int current = queue.front();
                queue.pop();

                const int cx = current % maskWidth;
                const int cy = current / maskWidth;
                minX = std::min(minX, cx);
                maxX = std::max(maxX, cx);
                minY = std::min(minY, cy);
                maxY = std::max(maxY, cy);
                lumaSum += lumaMap[current];
                uSum += uMap[current];
                vSum += vMap[current];
                ++area;

                bool boundaryPixel = false;

                for (int i = 0; i < 4; ++i) {
                    const int nx = cx + dx[i];
                    const int ny = cy + dy[i];
                    if (nx < 0 || ny < 0 || nx >= maskWidth || ny >= maskHeight) {
                        boundaryPixel = true;
                        continue;
                    }

                    const int nextIndex = ny * maskWidth + nx;
                    if (mask[nextIndex] == 0) {
                        boundaryPixel = true;
                        continue;
                    }
                    if (visited[nextIndex] != 0) {
                        continue;
                    }

                    visited[nextIndex] = 1;
                    queue.push(nextIndex);
                }

                if (boundaryPixel) {
                    ++perimeter;
                }
            }

            const int boxWidth = maxX - minX + 1;
            const int boxHeight = maxY - minY + 1;
            const int boxArea = boxWidth * boxHeight;
            if (area < 5 || boxArea <= 0) {
                continue;
            }

            if (minX <= 1 || minY <= 1 || maxX >= maskWidth - 2 || maxY >= maskHeight - 2) {
                continue;
            }

            if (boxWidth > maskWidth / 4 || boxHeight > maskHeight / 4) {
                continue;
            }

            const float aspect = static_cast<float>(boxWidth) / static_cast<float>(boxHeight);
            if (aspect < 0.6f || aspect > 1.4f) {
                continue;
            }

            const float fillRatio = static_cast<float>(area) / static_cast<float>(boxArea);
            if (fillRatio < 0.22f || fillRatio > 0.97f) {
                continue;
            }

            const float averageLuma = static_cast<float>(lumaSum) / static_cast<float>(area);
            const float averageU = static_cast<float>(uSum) / static_cast<float>(area);
            const float averageV = static_cast<float>(vSum) / static_cast<float>(area);
            if (averageLuma > 252.0f) {
                continue;
            }

            const int samplePadding = 2;
            int ringSum = 0;
            int ringCount = 0;
            for (int sy = std::max(0, minY - samplePadding); sy <= std::min(maskHeight - 1, maxY + samplePadding); ++sy) {
                for (int sx = std::max(0, minX - samplePadding); sx <= std::min(maskWidth - 1, maxX + samplePadding); ++sx) {
                    if (sx >= minX && sx <= maxX && sy >= minY && sy <= maxY) {
                        continue;
                    }
                    ringSum += lumaMap[sy * maskWidth + sx];
                    ++ringCount;
                }
            }

            const float backgroundLuma = ringCount > 0
                ? static_cast<float>(ringSum) / static_cast<float>(ringCount)
                : averageLuma;
            const float contrast = averageLuma - backgroundLuma;
            if (contrast < 3.0f) {
                continue;
            }

            const float circularity = (4.0f * kPi * static_cast<float>(area)) /
                std::max(1.0f, static_cast<float>(perimeter * perimeter));
            if (circularity < 0.18f) {
                continue;
            }

            const float compactness = 1.0f - std::min(std::abs(1.0f - aspect), 1.0f);
            const float normalizedLuma = std::min(averageLuma / 245.0f, 1.0f);
            const float normalizedContrast = std::min(contrast / 80.0f, 1.0f);
            const float colorScore = computeColorScore(averageLuma, averageU, averageV, ballProfile);
            const float minColorScore =
                ballProfile == PROFILE_AUTO ? 0.42f :
                (ballProfile == PROFILE_ORANGE ? 0.26f : 0.34f);
            if (colorScore < minColorScore) {
                continue;
            }
            const float score = fillRatio * 0.20f +
                compactness * 0.16f +
                circularity * 0.14f +
                normalizedContrast * 0.08f +
                normalizedLuma * 0.10f +
                colorScore * 0.52f;

            if (score > best.score) {
                best = Candidate{
                    minX,
                    minY,
                    maxX,
                    maxY,
                    area,
                    perimeter,
                    fillRatio,
                    averageLuma,
                    averageU,
                    averageV,
                    contrast,
                    colorScore,
                    score,
                };
            }
        }
    }

    return best;
}

}  // namespace

extern "C"
JNIEXPORT jfloatArray JNICALL
Java_com_example_tabletennistracker_NativeBallTracker_detectBall(
    JNIEnv* env,
    jobject /* this */,
    jbyteArray yPlane,
    jbyteArray uPlane,
    jbyteArray vPlane,
    jint width,
    jint height,
    jint rowStrideY,
    jint rowStrideUV,
    jint pixelStrideUV,
    jint ballProfile
) {
    if (width <= 0 || height <= 0) {
        return nullptr;
    }

    jbyte* yBytes = env->GetByteArrayElements(yPlane, nullptr);
    jbyte* uBytes = env->GetByteArrayElements(uPlane, nullptr);
    jbyte* vBytes = env->GetByteArrayElements(vPlane, nullptr);

    const int step = 2;
    const int maskWidth = width / step;
    const int maskHeight = height / step;
    if (maskWidth <= 0 || maskHeight <= 0) {
        env->ReleaseByteArrayElements(yPlane, yBytes, JNI_ABORT);
        env->ReleaseByteArrayElements(uPlane, uBytes, JNI_ABORT);
        env->ReleaseByteArrayElements(vPlane, vBytes, JNI_ABORT);
        return nullptr;
    }

    std::vector<uint8_t> mask(maskWidth * maskHeight, 0);
    std::vector<uint8_t> lumaMap(maskWidth * maskHeight, 0);
    std::vector<uint8_t> uMap(maskWidth * maskHeight, 0);
    std::vector<uint8_t> vMap(maskWidth * maskHeight, 0);

    for (int my = 0; my < maskHeight; ++my) {
        for (int mx = 0; mx < maskWidth; ++mx) {
            const int x = mx * step;
            const int y = my * step;
            const int uvX = x / 2;
            const int uvY = y / 2;

            const int luma = readY(yBytes, x, y, rowStrideY);
            const int u = readUV(uBytes, uvX, uvY, rowStrideUV, pixelStrideUV);
            const int v = readUV(vBytes, uvX, uvY, rowStrideUV, pixelStrideUV);

            const int index = my * maskWidth + mx;
            lumaMap[index] = static_cast<uint8_t>(luma);
            uMap[index] = static_cast<uint8_t>(u);
            vMap[index] = static_cast<uint8_t>(v);
            mask[index] = matchesBallProfile(luma, u, v, ballProfile) ? 1 : 0;
        }
    }

    env->ReleaseByteArrayElements(yPlane, yBytes, JNI_ABORT);
    env->ReleaseByteArrayElements(uPlane, uBytes, JNI_ABORT);
    env->ReleaseByteArrayElements(vPlane, vBytes, JNI_ABORT);

    Candidate best = findBestCandidate(mask, lumaMap, uMap, vMap, maskWidth, maskHeight, ballProfile);
    if (best.score <= 0.0f) {
        return nullptr;
    }

    const float padding = 4.0f * step;
    const float left = std::max(0.0f, best.minX * static_cast<float>(step) - padding);
    const float top = std::max(0.0f, best.minY * static_cast<float>(step) - padding);
    const float right = std::min(static_cast<float>(width), (best.maxX + 1) * static_cast<float>(step) + padding);
    const float bottom = std::min(static_cast<float>(height), (best.maxY + 1) * static_cast<float>(step) + padding);

    const float boxWidth = right - left;
    const float boxHeight = bottom - top;
    const float side = std::max(boxWidth, boxHeight);
    const float centerX = left + boxWidth * 0.5f;
    const float centerY = top + boxHeight * 0.5f;
    const float squareLeft = std::max(0.0f, centerX - side * 0.5f);
    const float squareTop = std::max(0.0f, centerY - side * 0.5f);
    const float squareRight = std::min(static_cast<float>(width), squareLeft + side);
    const float squareBottom = std::min(static_cast<float>(height), squareTop + side);
    const float confidence = std::min(0.99f, std::max(0.2f, best.score));

    jfloat output[5] = {squareLeft, squareTop, squareRight, squareBottom, confidence};
    jfloatArray result = env->NewFloatArray(5);
    env->SetFloatArrayRegion(result, 0, 5, output);
    return result;
}
