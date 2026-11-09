-- Read-only diagnostic queries. These never certify a recording or relax a gate.
CREATE PERFETTO TABLE lq_trace_health AS
SELECT name, idx, severity, source, value
FROM stats WHERE value != 0 AND severity != 'info' ORDER BY name, idx;

CREATE PERFETTO TABLE lq_app_long_slices AS
SELECT t.tid, t.name AS thread_name,
       s.name, s.ts, ROUND(s.dur / 1e6, 3) AS duration_ms
FROM slice s JOIN thread_track tt ON tt.id=s.track_id
JOIN thread t ON t.utid=tt.utid JOIN process p ON p.upid=t.upid
WHERE p.pid=15908 AND s.dur > 100000000 AND s.name NOT GLOB 'NPV10*'
ORDER BY s.dur DESC LIMIT 50;

CREATE PERFETTO TABLE lq_writer_bins AS
SELECT
       CAST((s.ts-22663600000000)/10000000000 AS INT) AS bin_10s,
       COUNT(*) AS frames, ROUND(AVG(s.dur)/1e6,3) AS mean_ms,
       ROUND(MAX(s.dur)/1e6,3) AS max_ms,
       ROUND((MAX(s.ts+s.dur)-MIN(s.ts))/1e9,3) AS span_seconds
FROM slice s JOIN thread_track tt ON tt.id=s.track_id
JOIN thread t ON t.utid=tt.utid JOIN process p ON p.upid=t.upid
WHERE p.pid=15908 AND s.name='NPV10 DMC async record write'
GROUP BY bin_10s ORDER BY bin_10s;

CREATE PERFETTO TABLE lq_writer_gaps AS
SELECT ts, end_ts, next_ts,
       ROUND((next_ts-end_ts)/1e6,3) AS idle_gap_ms
FROM (
  SELECT s.ts, s.ts+s.dur AS end_ts,
         LEAD(s.ts) OVER (ORDER BY s.ts) AS next_ts
  FROM slice s JOIN thread_track tt ON tt.id=s.track_id
  JOIN thread t ON t.utid=tt.utid JOIN process p ON p.upid=t.upid
  WHERE p.pid=15908 AND s.name='NPV10 DMC async record write'
) WHERE next_ts-end_ts > 80000000 ORDER BY idle_gap_ms DESC LIMIT 30;

CREATE PERFETTO TABLE lq_final_thread_states AS
SELECT t.tid, t.name,
       st.state, st.io_wait, st.blocked_function,
       ROUND(SUM(MIN(st.ts+st.dur,22908000000000)-MAX(st.ts,22900000000000))/1e6,3)
         AS duration_ms, COUNT(*) AS count
FROM thread_state st JOIN thread t ON t.utid=st.utid
JOIN process p ON p.upid=t.upid
WHERE p.pid=15908 AND st.dur>0 AND st.ts<22908000000000
  AND st.ts+st.dur>22900000000000
  AND (t.tid IN (16556,16559,16011,16005,15933) OR t.name GLOB 'NPV10*')
GROUP BY t.utid, st.state, st.io_wait, st.blocked_function
ORDER BY t.tid, duration_ms DESC;

CREATE PERFETTO TABLE lq_post_stop_states AS
SELECT t.tid, t.name,
       st.ts, ROUND(st.dur/1e6,3) AS duration_ms,
       st.state, st.io_wait, st.blocked_function
FROM thread_state st JOIN thread t ON t.utid=st.utid
JOIN process p ON p.upid=t.upid
WHERE p.pid=15908 AND st.ts>22906464000000 AND st.dur>2000000000
ORDER BY st.dur DESC LIMIT 35;

CREATE PERFETTO TABLE lq_kernel_event_types AS
SELECT name, COUNT(*) AS count
FROM ftrace_event WHERE name GLOB '*sync*' OR name GLOB 'block*'
GROUP BY name ORDER BY count DESC;

-- Trace Processor requires exactly one result-producing statement per query file.
SELECT 'trace_health' AS section,
       printf('%s index=%d severity=%s source=%s value=%d', name, idx, severity, source, value) AS detail
FROM lq_trace_health
UNION ALL
SELECT 'trace_bounds', printf('start=%d end=%d', start_ts, end_ts) FROM trace_bounds
UNION ALL
SELECT 'app_long_slices', printf('tid=%d thread=%s ts=%d dur_ms=%.3f slice=%s',
       tid, thread_name, ts, duration_ms, name) FROM lq_app_long_slices
UNION ALL
SELECT 'writer_10_second_bins', printf('bin=%d frames=%d mean_ms=%.3f max_ms=%.3f span_s=%.3f',
       bin_10s, frames, mean_ms, max_ms, span_seconds) FROM lq_writer_bins
UNION ALL
SELECT 'writer_inter_write_gaps', printf('ts=%d end=%d next=%d gap_ms=%.3f',
       ts, end_ts, next_ts, idle_gap_ms) FROM lq_writer_gaps
UNION ALL
SELECT 'final_8_seconds_thread_states', printf('tid=%d thread=%s state=%s io_wait=%s blocked=%s dur_ms=%.3f count=%d',
       tid, name, state, io_wait, blocked_function, duration_ms, count) FROM lq_final_thread_states
UNION ALL
SELECT 'post_stop_long_thread_states', printf('tid=%d thread=%s ts=%d dur_ms=%.3f state=%s io_wait=%s blocked=%s',
       tid, name, ts, duration_ms, state, io_wait, blocked_function) FROM lq_post_stop_states
UNION ALL
SELECT 'kernel_event_types', printf('name=%s count=%d', name, count) FROM lq_kernel_event_types;
