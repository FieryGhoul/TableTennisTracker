#include <jni.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <queue>
#include <vector>

namespace {

constexpr float kPi = 3.14159265358979323846f;
constexpr int kMaskStep = 2;
constexpr int kMaxDebugCandidates = 8;
constexpr float kMinConfidence = 0.18f;

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
    float circularity;
    float colorScore;
    float score;
};

struct DetectorDebug {
    Candidate best{0, 0, 0, 0, 0, 0, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f};
    std::vector<Candidate> visibleCandidates;
    int totalComponents = 0;
    int rejectedTooSmall = 0;
    int rejectedTooLarge = 0;
    int rejectedEdge = 0;
    int rejectedAspect = 0;
    int rejectedFill = 0;
    int rejectedLuma = 0;
    int rejectedContrast = 0;
    int rejectedCircularity = 0;
    int rejectedColor = 0;
    int rejectedConfidence = 0;
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
    const float orangeDistanceScore = std::clamp(1.0f - (orangeDistance / 78.0f), 0.0f, 1.0f);
    const float orangeSeparation = std::clamp(((averageV - averageU) - 10.0f) / 72.0f, 0.0f, 1.0f);
    const float orangeBrightness = std::clamp((averageLuma - 70.0f) / 100.0f, 0.0f, 1.0f);
    const float orangeScore = orangeDistanceScore * 0.42f + orangeSeparation * 0.34f + orangeBrightness * 0.24f;

    const float whiteChromaticDistance =
        (std::abs(averageU - 128.0f) + std::abs(averageV - 128.0f)) / 2.0f;
    const float whiteNeutrality = std::clamp(1.0f - (whiteChromaticDistance / 26.0f), 0.0f, 1.0f);
    const float whiteBrightness = std::clamp((averageLuma - 160.0f) / 70.0f, 0.0f, 1.0f);
    const float whiteScore = whiteNeutrality * 0.62f + whiteBrightness * 0.38f;

    if (ballProfile == PROFILE_ORANGE) {
        return orangeScore;
    }
    if (ballProfile == PROFILE_WHITE) {
        return whiteScore;
    }
    return std::max(orangeScore, whiteScore);
}

float minimumColorScoreForProfile(int ballProfile) {
    if (ballProfile == PROFILE_ORANGE) {
        return 0.18f;
    }
    if (ballProfile == PROFILE_WHITE) {
        return 0.24f;
    }
    return 0.20f;
}

void insertVisibleCandidate(std::vector<Candidate>& candidates, const Candidate& candidate) {
    candidates.push_back(candidate);
    std::sort(
        candidates.begin(),
        candidates.end(),
        [](const Candidate& left, const Candidate& right) {
            return left.score > right.score;
        }
    );
    if (static_cast<int>(candidates.size()) > kMaxDebugCandidates) {
        candidates.resize(kMaxDebugCandidates);
    }
}

Candidate makePaddedSquareCandidate(const Candidate& candidate, int width, int height) {
    const float padding = 4.0f * static_cast<float>(kMaskStep);
    const float left = std::max(0.0f, candidate.minX * static_cast<float>(kMaskStep) - padding);
    const float top = std::max(0.0f, candidate.minY * static_cast<float>(kMaskStep) - padding);
    const float right = std::min(static_cast<float>(width), (candidate.maxX + 1) * static_cast<float>(kMaskStep) + padding);
    const float bottom = std::min(static_cast<float>(height), (candidate.maxY + 1) * static_cast<float>(kMaskStep) + padding);

    const float boxWidth = right - left;
    const float boxHeight = bottom - top;
    const float side = std::max(boxWidth, boxHeight);
    const float centerX = left + boxWidth * 0.5f;
    const float centerY = top + boxHeight * 0.5f;

    Candidate square = candidate;
    square.minX = static_cast<int>(std::round(std::max(0.0f, centerX - side * 0.5f)));
    square.minY = static_cast<int>(std::round(std::max(0.0f, centerY - side * 0.5f)));
    square.maxX = static_cast<int>(std::round(std::min(static_cast<float>(width), square.minX + side)));
    square.maxY = static_cast<int>(std::round(std::min(static_cast<float>(height), square.minY + side)));
    return square;
}

DetectorDebug runDetector(
    const std::vector<uint8_t>& mask,
    const std::vector<uint8_t>& lumaMap,
    const std::vector<uint8_t>& uMap,
    const std::vector<uint8_t>& vMap,
    int maskWidth,
    int maskHeight,
    int width,
    int height,
    int ballProfile
) {
    DetectorDebug debug;
    std::vector<uint8_t> visited(maskWidth * maskHeight, 0);
    const std::array<int, 4> dx{1, -1, 0, 0};
    const std::array<int, 4> dy{0, 0, 1, -1};

    for (int y = 0; y < maskHeight; ++y) {
        for (int x = 0; x < maskWidth; ++x) {
            const int index = y * maskWidth + x;
            if (mask[index] == 0 || visited[index] != 0) {
                continue;
            }

            ++debug.totalComponents;

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
            const int minDimension = std::min(boxWidth, boxHeight);
            const int minimumArea =
                ballProfile == PROFILE_ORANGE ? 24 :
                (ballProfile == PROFILE_WHITE ? 8 : 12);
            const int minimumDimension =
                ballProfile == PROFILE_ORANGE ? 12 :
                (ballProfile == PROFILE_WHITE ? 5 : 7);
            if (area < minimumArea || boxArea <= 0 || minDimension < minimumDimension) {
                ++debug.rejectedTooSmall;
                continue;
            }

            if (minX <= 1 || minY <= 1 || maxX >= maskWidth - 2 || maxY >= maskHeight - 2) {
                ++debug.rejectedEdge;
                continue;
            }

            if (boxWidth > maskWidth / 4 || boxHeight > maskHeight / 4) {
                ++debug.rejectedTooLarge;
                continue;
            }

            const float aspect = static_cast<float>(boxWidth) / static_cast<float>(boxHeight);
            const float minAspect = ballProfile == PROFILE_ORANGE ? 0.40f : 0.45f;
            const float maxAspect = ballProfile == PROFILE_ORANGE ? 2.40f : 1.65f;
            if (aspect < minAspect || aspect > maxAspect) {
                ++debug.rejectedAspect;
                continue;
            }

            const float fillRatio = static_cast<float>(area) / static_cast<float>(boxArea);
            if (fillRatio < 0.15f || fillRatio > 0.98f) {
                ++debug.rejectedFill;
                continue;
            }

            const float averageLuma = static_cast<float>(lumaSum) / static_cast<float>(area);
            const float averageU = static_cast<float>(uSum) / static_cast<float>(area);
            const float averageV = static_cast<float>(vSum) / static_cast<float>(area);
            if (averageLuma > 252.0f) {
                ++debug.rejectedLuma;
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
            if (contrast < 2.0f) {
                ++debug.rejectedContrast;
                continue;
            }

            const float circularity = (4.0f * kPi * static_cast<float>(area)) /
                std::max(1.0f, static_cast<float>(perimeter * perimeter));
            if (circularity < 0.08f) {
                ++debug.rejectedCircularity;
                continue;
            }

            const float compactness = 1.0f - std::min(std::abs(1.0f - aspect), 1.0f);
            const float normalizedLuma = std::min(averageLuma / 245.0f, 1.0f);
            const float normalizedContrast = std::min(contrast / 80.0f, 1.0f);
            const float colorScore = computeColorScore(averageLuma, averageU, averageV, ballProfile);
            const float sizeScore = std::clamp(
                static_cast<float>(minDimension - minimumDimension) /
                    static_cast<float>(std::max(1, maskHeight / 7 - minimumDimension)),
                0.0f,
                1.0f
            );
            const float areaScore = std::clamp(
                static_cast<float>(area) /
                    static_cast<float>(std::max(1, (maskWidth * maskHeight) / 30)),
                0.0f,
                1.0f
            );
            const float score =
                ballProfile == PROFILE_ORANGE
                    ? fillRatio * 0.06f +
                        compactness * 0.06f +
                        circularity * 0.08f +
                        normalizedContrast * 0.06f +
                        normalizedLuma * 0.02f +
                        colorScore * 0.26f +
                        sizeScore * 0.24f +
                        areaScore * 0.22f
                    : fillRatio * 0.10f +
                        compactness * 0.08f +
                        circularity * 0.10f +
                        normalizedContrast * 0.08f +
                        normalizedLuma * 0.04f +
                        colorScore * 0.34f +
                        sizeScore * 0.16f +
                        areaScore * 0.10f;

            Candidate candidate{
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
                circularity,
                colorScore,
                score,
            };

            if (colorScore < minimumColorScoreForProfile(ballProfile)) {
                ++debug.rejectedColor;
                continue;
            }

            insertVisibleCandidate(debug.visibleCandidates, makePaddedSquareCandidate(candidate, width, height));

            if (score < kMinConfidence) {
                ++debug.rejectedConfidence;
                continue;
            }

            Candidate selected = makePaddedSquareCandidate(candidate, width, height);
            if (selected.score > debug.best.score) {
                debug.best = selected;
            }
        }
    }

    if (debug.best.score <= 0.0f && !debug.visibleCandidates.empty()) {
        // If nothing cleared the final confidence gate, still surface the strongest
        // visible candidate so the app can show one best box instead of only dots.
        debug.best = debug.visibleCandidates.front();
    }

    return debug;
}

}  // namespace

extern "C"
JNIEXPORT jfloatArray JNICALL
Java_com_example_tabletennistracker_NativeBallTracker_detectBallDebug(
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

    const int maskWidth = width / kMaskStep;
    const int maskHeight = height / kMaskStep;
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
            const int x = mx * kMaskStep;
            const int y = my * kMaskStep;
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

    DetectorDebug debug = runDetector(
        mask,
        lumaMap,
        uMap,
        vMap,
        maskWidth,
        maskHeight,
        width,
        height,
        ballProfile
    );

    const bool detectionFound = debug.best.score > 0.0f;
    const int headerSize = 21;
    const int candidateStride = 8;
    const int candidateCount = static_cast<int>(debug.visibleCandidates.size());
    const int totalSize = headerSize + candidateCount * candidateStride;

    std::vector<jfloat> output(totalSize, 0.0f);
    output[0] = detectionFound ? 1.0f : 0.0f;
    output[1] = static_cast<jfloat>(candidateCount);
    output[2] = static_cast<jfloat>(debug.totalComponents);
    output[3] = static_cast<jfloat>(debug.rejectedTooSmall);
    output[4] = static_cast<jfloat>(debug.rejectedTooLarge);
    output[5] = static_cast<jfloat>(debug.rejectedEdge);
    output[6] = static_cast<jfloat>(debug.rejectedAspect);
    output[7] = static_cast<jfloat>(debug.rejectedFill);
    output[8] = static_cast<jfloat>(debug.rejectedLuma);
    output[9] = static_cast<jfloat>(debug.rejectedContrast);
    output[10] = static_cast<jfloat>(debug.rejectedCircularity);
    output[11] = static_cast<jfloat>(debug.rejectedColor);
    output[12] = static_cast<jfloat>(debug.rejectedConfidence);
    output[13] = static_cast<jfloat>(debug.best.minX);
    output[14] = static_cast<jfloat>(debug.best.minY);
    output[15] = static_cast<jfloat>(debug.best.maxX);
    output[16] = static_cast<jfloat>(debug.best.maxY);
    output[17] = debug.best.score;
    output[18] = debug.best.colorScore;
    output[19] = debug.best.contrast;
    output[20] = debug.best.circularity;

    for (int index = 0; index < candidateCount; ++index) {
        const Candidate& candidate = debug.visibleCandidates[index];
        const int offset = headerSize + index * candidateStride;
        output[offset] = static_cast<jfloat>(candidate.minX);
        output[offset + 1] = static_cast<jfloat>(candidate.minY);
        output[offset + 2] = static_cast<jfloat>(candidate.maxX);
        output[offset + 3] = static_cast<jfloat>(candidate.maxY);
        output[offset + 4] = candidate.score;
        output[offset + 5] = candidate.colorScore;
        output[offset + 6] = candidate.contrast;
        output[offset + 7] = candidate.circularity;
    }

    jfloatArray result = env->NewFloatArray(totalSize);
    env->SetFloatArrayRegion(result, 0, totalSize, output.data());
    return result;
}
