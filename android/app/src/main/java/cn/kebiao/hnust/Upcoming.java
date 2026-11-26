package cn.kebiao.hnust;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/** 从本地缓存的课表里查课程（桌面小组件用）：某天的全部课、正在上/下一节课。 */
class Upcoming {

    static class Info {
        String name = "";
        String location = "";
        String teacher = "";
        LocalDate date;
        LocalTime start;
        LocalTime end;
        boolean ongoing;
    }

    /** 某一天的全部课程，按开始时间排序；没有缓存就返回空表。 */
    static List<Info> dayCourses(Store store, LocalDate date) {
        List<Info> out = new ArrayList<>();
        String termText = store.termStart();
        if (termText == null || termText.isEmpty()) return out;
        LocalDate term;
        try {
            term = LocalDate.parse(termText);
        } catch (Exception e) {
            return out;
        }
        long days = ChronoUnit.DAYS.between(term, date);
        int week = (int) (days / 7) + 1;
        if (week < 1 || week > store.totalWeeks()) return out;
        String json = store.weekCache(week);
        if (json == null || json.isEmpty()) return out;
        List<String[]> sections = store.sections();
        try {
            JSONObject shaped = new JSONObject(json);
            JSONArray dayArr = shaped.optJSONArray("days");
            if (dayArr == null) return out;
            for (int i = 0; i < dayArr.length(); i++) {
                JSONObject day = dayArr.optJSONObject(i);
                if (day == null || !date.toString().equals(day.optString("date"))) continue;
                JSONArray courses = day.optJSONArray("courses");
                if (courses == null) break;
                for (int j = 0; j < courses.length(); j++) {
                    JSONObject c = courses.optJSONObject(j);
                    if (c == null) continue;
                    int startSec = c.optInt("start", 0);
                    int endSec = c.optInt("end", startSec);
                    if (startSec < 1 || startSec > sections.size() || endSec < 1 || endSec > sections.size()) {
                        continue;
                    }
                    Info info = new Info();
                    info.name = c.optString("name");
                    info.location = c.optString("location");
                    info.teacher = c.optString("teacher");
                    info.date = date;
                    try {
                        info.start = LocalTime.parse(sections.get(startSec - 1)[0]);
                        info.end = LocalTime.parse(sections.get(endSec - 1)[1]);
                    } catch (Exception e) {
                        continue;
                    }
                    out.add(info);
                }
                break;
            }
        } catch (Exception ignored) {
        }
        out.sort((a, b) -> a.start.compareTo(b.start));
        return out;
    }

    /** 正在上的课优先，其次是接下来最近的一节（最多向后找 14 天）。 */
    static Info currentOrNext(Store store) {
        LocalDateTime now = LocalDateTime.now();
        List<Info> today = dayCourses(store, LocalDate.now());
        Info next = null;
        for (Info info : today) {
            LocalDateTime s = LocalDateTime.of(info.date, info.start);
            LocalDateTime e = LocalDateTime.of(info.date, info.end);
            if (!now.isBefore(s) && now.isBefore(e)) {
                info.ongoing = true;
                return info;
            }
            if (s.isAfter(now) && next == null) next = info;
        }
        if (next != null) return next;
        for (int offset = 1; offset < 14; offset++) {
            List<Info> list = dayCourses(store, LocalDate.now().plusDays(offset));
            if (!list.isEmpty()) return list.get(0);
        }
        return null;
    }

    static String hhmm(LocalTime t) {
        return String.format("%02d:%02d", t.getHour(), t.getMinute());
    }

    static String dayLabel(LocalDate date) {
        LocalDate today = LocalDate.now();
        if (date.equals(today)) return "今天";
        if (date.equals(today.plusDays(1))) return "明天";
        if (date.equals(today.plusDays(2))) return "后天";
        return date.getMonthValue() + "月" + date.getDayOfMonth() + "日";
    }
}
