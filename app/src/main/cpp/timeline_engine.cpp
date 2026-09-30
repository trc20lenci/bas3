#include "timeline_engine.h"

#include <algorithm>
#include <cstdlib>
#include <limits>
#include <sstream>

namespace base {

namespace {
constexpr int64_t kInf = std::numeric_limits<int64_t>::max() / 4;
constexpr size_t kMaxHistory = 100;

int64_t clampi(int64_t v, int64_t lo, int64_t hi) { return v < lo ? lo : (v > hi ? hi : v); }
}  // namespace

Clip* TimelineEngine::find(int64_t id) {
    for (auto& c : clips_) if (c.id == id) return &c;
    return nullptr;
}

int64_t TimelineEngine::addClip(int row, int type, const std::string& uri,
                                int64_t srcDurMs, int64_t lengthMs) {
    std::lock_guard<std::mutex> g(mu_);
    int64_t start = 0;
    for (auto& c : clips_) if (c.row == row) start = std::max(start, c.endMs);
    Clip c;
    c.id = nextId_++;
    c.row = row;
    c.type = type;
    c.startMs = start;
    c.endMs = start + std::max<int64_t>(lengthMs, kMinClipMs);
    c.srcDurMs = srcDurMs;
    c.uri = uri;
    clips_.push_back(c);
    return c.id;
}

bool TimelineEngine::moveClip(int64_t id, int64_t newStartMs, int64_t thr, int64_t extraSnap) {
    std::lock_guard<std::mutex> g(mu_);
    Clip* c = find(id);
    if (!c) return false;
    const int64_t len = c->length();

    std::vector<const Clip*> others;
    for (auto& o : clips_) if (o.row == c->row && o.id != id) others.push_back(&o);
    std::sort(others.begin(), others.end(),
              [](const Clip* a, const Clip* b) { return a->startMs < b->startMs; });

    // 1) магнит к краям
    int64_t desired = newStartMs;
    int64_t best = thr + 1;
    auto trySnap = [&](int64_t target) {
        int64_t d = std::llabs(desired - target);
        if (d <= thr && d < best) { best = d; newStartMs = target; }
    };
    trySnap(0);
    if (extraSnap >= 0) { trySnap(extraSnap); trySnap(extraSnap - len); }
    for (auto* o : others) { trySnap(o->endMs); trySnap(o->startMs - len); }
    desired = (best <= thr) ? newStartMs : desired;

    // 2) ближайший свободный промежуток нужной длины
    int64_t result = -1, resultDist = kInf;
    int64_t gapLo = 0;
    auto consider = [&](int64_t lo, int64_t hi) {  // hi = правая граница промежутка
        if (hi - lo < len) return;
        int64_t cand = clampi(desired, lo, hi - len);
        int64_t dist = std::llabs(cand - desired);
        if (dist < resultDist) { resultDist = dist; result = cand; }
    };
    for (auto* o : others) { consider(gapLo, o->startMs); gapLo = std::max(gapLo, o->endMs); }
    consider(gapLo, kInf);

    if (result < 0) return false;
    c->startMs = result;
    c->endMs = result + len;
    return true;
}

bool TimelineEngine::trimStart(int64_t id, int64_t newStartMs) {
    std::lock_guard<std::mutex> g(mu_);
    Clip* c = find(id);
    if (!c) return false;
    int64_t prevEnd = 0;
    for (auto& o : clips_) if (o.row == c->row && o.id != id && o.endMs <= c->startMs) prevEnd = std::max(prevEnd, o.endMs);
    int64_t lo = prevEnd;
    const bool bounded = c->srcDurMs > 0;
    if (bounded) lo = std::max(lo, c->startMs - c->srcInMs);
    int64_t hi = c->endMs - kMinClipMs;
    int64_t v = clampi(newStartMs, lo, hi);
    if (bounded) c->srcInMs += v - c->startMs;
    c->startMs = v;
    return true;
}

bool TimelineEngine::trimEnd(int64_t id, int64_t newEndMs) {
    std::lock_guard<std::mutex> g(mu_);
    Clip* c = find(id);
    if (!c) return false;
    int64_t nextStart = kInf;
    for (auto& o : clips_) if (o.row == c->row && o.id != id && o.startMs >= c->endMs) nextStart = std::min(nextStart, o.startMs);
    int64_t hi = nextStart;
    if (c->srcDurMs > 0) hi = std::min(hi, c->startMs + (c->srcDurMs - c->srcInMs));
    int64_t lo = c->startMs + kMinClipMs;
    c->endMs = clampi(newEndMs, lo, std::max(lo, hi));
    return true;
}

int64_t TimelineEngine::split(int64_t id, int64_t at) {
    std::lock_guard<std::mutex> g(mu_);
    Clip* c = find(id);
    if (!c || at < c->startMs + kMinClipMs || at > c->endMs - kMinClipMs) return -1;
    Clip right = *c;
    right.id = nextId_++;
    right.startMs = at;
    if (c->srcDurMs > 0) right.srcInMs = c->srcInMs + (at - c->startMs);
    c->endMs = at;
    int64_t newId = right.id;
    clips_.push_back(right);  // find() уже недействителен после push_back
    return newId;
}

bool TimelineEngine::remove(int64_t id) {
    std::lock_guard<std::mutex> g(mu_);
    auto it = std::find_if(clips_.begin(), clips_.end(), [&](const Clip& c) { return c.id == id; });
    if (it == clips_.end()) return false;
    const int row = it->row;
    const int64_t s = it->startMs, len = it->length();
    clips_.erase(it);
    if (row == 0) {
        for (auto& o : clips_) if (o.row == 0 && o.startMs >= s) { o.startMs -= len; o.endMs -= len; }
    }
    return true;
}

void TimelineEngine::checkpoint() {
    std::lock_guard<std::mutex> g(mu_);
    undo_.push_back(clips_);
    if (undo_.size() > kMaxHistory) undo_.erase(undo_.begin());
    redo_.clear();
}

bool TimelineEngine::same(const std::vector<Clip>& a, const std::vector<Clip>& b) {
    if (a.size() != b.size()) return false;
    for (size_t i = 0; i < a.size(); ++i) {
        const Clip &x = a[i], &y = b[i];
        if (x.id != y.id || x.row != y.row || x.startMs != y.startMs || x.endMs != y.endMs || x.srcInMs != y.srcInMs)
            return false;
    }
    return true;
}

void TimelineEngine::discardCheckpointIfNoop() {
    std::lock_guard<std::mutex> g(mu_);
    if (!undo_.empty() && same(undo_.back(), clips_)) undo_.pop_back();
}

bool TimelineEngine::undo() {
    std::lock_guard<std::mutex> g(mu_);
    if (undo_.empty()) return false;
    redo_.push_back(clips_);
    clips_ = std::move(undo_.back());
    undo_.pop_back();
    return true;
}

bool TimelineEngine::redo() {
    std::lock_guard<std::mutex> g(mu_);
    if (redo_.empty()) return false;
    undo_.push_back(clips_);
    clips_ = std::move(redo_.back());
    redo_.pop_back();
    return true;
}

bool TimelineEngine::canUndo() const { std::lock_guard<std::mutex> g(mu_); return !undo_.empty(); }
bool TimelineEngine::canRedo() const { std::lock_guard<std::mutex> g(mu_); return !redo_.empty(); }

int64_t TimelineEngine::totalMs() const {
    std::lock_guard<std::mutex> g(mu_);
    int64_t t = 0;
    for (auto& c : clips_) t = std::max(t, c.endMs);
    return t;
}

// Формат: "V1\t<nextId>\n" + по строке на клип:
// id \t row \t type \t start \t end \t srcIn \t srcDur \t uri
std::string TimelineEngine::serialize() const {
    std::lock_guard<std::mutex> g(mu_);
    std::ostringstream os;
    os << "V1\t" << nextId_ << "\n";
    for (auto& c : clips_)
        os << c.id << '\t' << c.row << '\t' << c.type << '\t' << c.startMs << '\t' << c.endMs << '\t'
           << c.srcInMs << '\t' << c.srcDurMs << '\t' << c.uri << '\n';
    return os.str();
}

bool TimelineEngine::deserialize(const std::string& data) {
    std::vector<Clip> parsed;
    int64_t next = 1;
    std::istringstream is(data);
    std::string line;
    bool header = false;
    while (std::getline(is, line)) {
        if (line.empty()) continue;
        std::vector<std::string> f;
        size_t p = 0, q;
        while ((q = line.find('\t', p)) != std::string::npos && f.size() < 7) { f.push_back(line.substr(p, q - p)); p = q + 1; }
        f.push_back(line.substr(p));
        try {
            if (!header) {
                if (f.size() < 2 || f[0] != "V1") return false;
                next = std::stoll(f[1]);
                header = true;
                continue;
            }
            if (f.size() < 8) continue;
            Clip c;
            c.id = std::stoll(f[0]); c.row = std::stoi(f[1]); c.type = std::stoi(f[2]);
            c.startMs = std::stoll(f[3]); c.endMs = std::stoll(f[4]);
            c.srcInMs = std::stoll(f[5]); c.srcDurMs = std::stoll(f[6]);
            c.uri = f[7];
            parsed.push_back(std::move(c));
        } catch (...) { return false; }
    }
    if (!header) return false;
    std::lock_guard<std::mutex> g(mu_);
    clips_ = std::move(parsed);
    nextId_ = next;
    undo_.clear();
    redo_.clear();
    return true;
}

}  // namespace base
