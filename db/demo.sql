-- 可选的本地演示数据。先初始化 schema.sql，并通过前端提供 public/zhiguang-demo/ 中的静态文件。
-- 显式导入到本地开发库，可重复运行。示例作者没有登录凭据。
SET NAMES utf8mb4;

START TRANSACTION;

INSERT INTO users (zg_id, nickname, bio, tags_json)
VALUES
    ('demo-study-lin', '林页', '记录学习方法和读书实践。', JSON_ARRAY('学习笔记')),
    ('demo-city-zhou', '周棠', '用照片记下每天路过的地方。', JSON_ARRAY('城市观察')),
    ('demo-design-xu', '许禾', '做产品设计，也做一点手冲。', JSON_ARRAY('设计实践')),
    ('demo-engineer-chen', '陈序', '记录排障和技术决策。', JSON_ARRAY('软件工程'))
ON DUPLICATE KEY UPDATE id = users.id;

INSERT INTO know_posts (
    id, creator_id, title, description, tags, img_urls,
    content_url, status, visible, create_time, update_time, publish_time
)
SELECT
    demo.post_id, u.id, demo.post_title, demo.summary, demo.post_tags, demo.images,
    CONCAT('/zhiguang-demo/', demo.slug, '.md'), 'published', 'public',
    CURRENT_TIMESTAMP - INTERVAL demo.hours_ago HOUR,
    CURRENT_TIMESTAMP - INTERVAL demo.hours_ago HOUR,
    CURRENT_TIMESTAMP - INTERVAL demo.hours_ago HOUR
FROM (
    SELECT
        990601000001 AS post_id, 'demo-study-lin' AS handle, 'recall' AS slug, 2 AS hours_ago,
        '合上书以后，才知道自己学会了什么' AS post_title,
        '主动回忆、错题卡和复习间隔，我目前用得最顺手的一套做法。' AS summary,
        JSON_ARRAY('学习方法', '主动回忆') AS post_tags,
        JSON_ARRAY('/zhiguang-demo/recall.webp', '/zhiguang-demo/library.webp') AS images
    UNION ALL SELECT
        990601000002, 'demo-city-zhou', 'rain', 5,
        '在雨天拍出有层次的街景',
        '从玻璃上的雨滴到街面的反光，用手机也能拍出前后景。',
        JSON_ARRAY('手机摄影', '城市观察'), JSON_ARRAY('/zhiguang-demo/rain.webp')
    UNION ALL SELECT
        990601000003, 'demo-design-xu', 'feedback', 9,
        '一个保存按钮，需要哪三种反馈？',
        '从等待、成功到失败，把操作反馈画进同一张流程草图。',
        JSON_ARRAY('交互设计', '产品笔记'), JSON_ARRAY('/zhiguang-demo/feedback.webp')
    UNION ALL SELECT
        990601000004, 'demo-engineer-chen', 'trace', 14,
        '用一条请求讲清楚什么是链路追踪',
        '对比快慢请求，沿时间轴找出真正消耗时间的步骤。',
        JSON_ARRAY('软件工程', '可观测性', '排障'), JSON_ARRAY('/zhiguang-demo/trace.webp')
    UNION ALL SELECT
        990601000005, 'demo-design-xu', 'coffee', 26,
        '手冲咖啡，从一组能复现的参数开始',
        '记录一次冲煮，再用两杯对照找出合适的研磨度。',
        JSON_ARRAY('手冲咖啡', '生活实验'), JSON_ARRAY('/zhiguang-demo/coffee.webp')
    UNION ALL SELECT
        990601000006, 'demo-study-lin', 'review', 20,
        '十分钟周复盘，我只留这四行',
        '把做过的事、卡住的地方和下周的第一步写清楚。',
        JSON_ARRAY('复盘', '时间管理'), JSON_ARRAY()
    UNION ALL SELECT
        990601000007, 'demo-city-zhou', 'canal', 33,
        '沿着河，做一次不赶路的散步',
        '三个观察点，加一句只有走过才写得出的记录。',
        JSON_ARRAY('城市漫步', '生活观察'), JSON_ARRAY('/zhiguang-demo/canal.webp')
    UNION ALL SELECT
        990601000008, 'demo-study-lin', 'reading', 50,
        '读书便签怎样留下可用的线索',
        '用问题、例子和行动三种标记，把值得重看的页码留下来。',
        JSON_ARRAY('阅读', '笔记方法'), JSON_ARRAY('/zhiguang-demo/reading.webp')
    UNION ALL SELECT
        990601000009, 'demo-engineer-chen', 'tradeoff', 40,
        '怎么写一条过几周还能看懂的技术决策',
        '记录当时的约束、接受的成本和重新评估的时机。',
        JSON_ARRAY('技术决策', '团队协作'), JSON_ARRAY()
    UNION ALL SELECT
        990601000010, 'demo-city-zhou', 'map', 62,
        '第一次走一条陌生的周末步道',
        '准备离线地图、返程站点和几个可调整的时间段。',
        JSON_ARRAY('周末徒步', '路线规划'), JSON_ARRAY('/zhiguang-demo/map.webp')
    UNION ALL SELECT
        990601000011, 'demo-study-lin', 'library', 91,
        '在图书馆找一个适合长期阅读的位置',
        '按光线、动线和手头的任务，挑一个愿意再去的座位。',
        JSON_ARRAY('阅读空间', '图书馆'), JSON_ARRAY('/zhiguang-demo/library.webp')
    UNION ALL SELECT
        990601000012, 'demo-engineer-chen', 'explain', 76,
        '解释一个新概念，用一个例子和一个边界',
        '从找书说到缓存，再问一次书被借走后怎么办。',
        JSON_ARRAY('技术写作', '知识分享'), JSON_ARRAY()
) AS demo
JOIN users u ON u.zg_id = demo.handle
ON DUPLICATE KEY UPDATE id = know_posts.id;

-- 沿用发布知文的 Outbox 事件，供 Canal / Kafka 消费者更新搜索索引。
INSERT INTO outbox (id, aggregate_type, aggregate_id, type, payload)
SELECT
    p.id + 100, 'knowpost', p.id, 'KnowPostPublished',
    JSON_OBJECT('entity', 'knowpost', 'op', 'upsert', 'id', p.id)
FROM know_posts p
WHERE p.id BETWEEN 990601000001 AND 990601000012
    AND p.content_url LIKE '/zhiguang-demo/%'
    AND NOT EXISTS (SELECT 1 FROM outbox o WHERE o.id = p.id + 100)
ON DUPLICATE KEY UPDATE id = outbox.id;

COMMIT;
