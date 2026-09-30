#pragma once
// Модель таймлайна BASE. Повторяет структуру react-timeline-editor:
//   rows (дорожки) -> actions (клипы на шкале времени: start/end),
// но время хранится в целых миллисекундах, чтобы не копить ошибку float.
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

namespace base {

enum ClipType : int { kVideo = 0, kImage = 1, kAudio = 2, kText = 3 };

struct Clip {
    int64_t id = 0;
    int row = 0;            // 0 = основная видеодорожка, 1 = аудио, 2 = текст
    int type = kVideo;
    int64_t startMs = 0;    // позиция на шкале
    int64_t endMs = 0;
    int64_t srcInMs = 0;    // смещение внутри исходника
    int64_t srcDurMs = 0;   // длина исходника; <= 0 -> не ограничен (фото, текст)
    std::string uri;
    int64_t length() const { return endMs - startMs; }
};

class TimelineEngine {
public:
    static constexpr int64_t kMinClipMs = 100;

    // Добавляет клип в конец дорожки. Возвращает id.
    int64_t addClip(int row, int type, const std::string& uri, int64_t srcDurMs, int64_t lengthMs);

    // newStartMs — «сырая» желаемая позиция от начала жеста. Движок сам:
    // притягивает к краям соседей / нулю / extraSnapMs (плейхед; -1 = нет)
    // и выбирает ближайший свободный промежуток на дорожке (клипы можно «перепрыгивать»).
    bool moveClip(int64_t id, int64_t newStartMs, int64_t snapThresholdMs, int64_t extraSnapMs);
    bool trimStart(int64_t id, int64_t newStartMs);
    bool trimEnd(int64_t id, int64_t newEndMs);
    // Возвращает id нового (правого) клипа или -1.
    int64_t split(int64_t id, int64_t atMs);
    // На основной дорожке удаление «схлопывает» пустоту (ripple).
    bool remove(int64_t id);

    // Undo/redo: checkpoint() вызывается один раз ПЕРЕД жестом/операцией.
    void checkpoint();
    void discardCheckpointIfNoop();
    bool undo();
    bool redo();
    bool canUndo() const;
    bool canRedo() const;

    int64_t totalMs() const;
    std::string serialize() const;
    bool deserialize(const std::string& data);

private:
    Clip* find(int64_t id);
    static bool same(const std::vector<Clip>& a, const std::vector<Clip>& b);

    mutable std::mutex mu_;
    std::vector<Clip> clips_;
    std::vector<std::vector<Clip>> undo_, redo_;
    int64_t nextId_ = 1;
};

}  // namespace base
