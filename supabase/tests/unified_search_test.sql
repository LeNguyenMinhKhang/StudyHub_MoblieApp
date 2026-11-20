-- Unified search tests (OpenSpec change add-unified-search).
-- Local disposable database only: see supabase/tests/local_supabase_shim.sql for
-- the runner. Everything runs in one transaction and is rolled back.
-- Synthetic fixture ids: users 1001-1008 and 2001-2150, posts 3001+, files 4001+.

\set ON_ERROR_STOP 1
begin;

-- ---------------------------------------------------------------- fixture
insert into auth.users (id, raw_user_meta_data) values
    ('00000000-0000-4000-8000-000000001001', '{"username":"author_a","display_name":"Tác Giả A"}'),
    ('00000000-0000-4000-8000-000000001002', '{"username":"friend_f","display_name":"Bạn Bè F"}'),
    ('00000000-0000-4000-8000-000000001003', '{"username":"stranger_s","display_name":"Người Lạ S"}'),
    ('00000000-0000-4000-8000-000000001004', '{"username":"banned_x","display_name":"Nguyễn Văn Khóa"}'),
    ('00000000-0000-4000-8000-000000001005', '{"username":"nguyenvana","display_name":"Nguyễn Văn An"}'),
    ('00000000-0000-4000-8000-000000001006', '{"username":"blocker_b","display_name":"Nguyễn Văn Bình"}'),
    ('00000000-0000-4000-8000-000000001007', '{"username":"blocked_c","display_name":"Nguyễn Văn Cường"}'),
    ('00000000-0000-4000-8000-000000001008', '{"username":"many_m","display_name":"Nhiều Bạn M"}');
insert into auth.users (id, raw_user_meta_data)
select ('00000000-0000-4000-8000-00000000' || (2000 + g)::text)::uuid,
       jsonb_build_object('username', 'mf_' || lpad(g::text, 3, '0'), 'display_name', 'Bạn số ' || lpad(g::text, 3, '0'))
from generate_series(1, 150) g;
update public.profiles set is_banned = true where id = '00000000-0000-4000-8000-000000001004';

insert into public.friendships (requester_id, addressee_id, status) values
    ('00000000-0000-4000-8000-000000001001', '00000000-0000-4000-8000-000000001002', 'accepted');
-- mf_150 is the oldest of M's 150 friendships, i.e. beyond the first 100 by recency.
insert into public.friendships (requester_id, addressee_id, status, created_at)
select '00000000-0000-4000-8000-000000001008', ('00000000-0000-4000-8000-00000000' || (2000 + g)::text)::uuid,
       'accepted', now() - make_interval(mins => g)
from generate_series(1, 150) g;
insert into public.blocks (blocker_id, blocked_id) values
    ('00000000-0000-4000-8000-000000001006', '00000000-0000-4000-8000-000000001001'),
    ('00000000-0000-4000-8000-000000001001', '00000000-0000-4000-8000-000000001007');

insert into public.subjects (name, description, is_active) values
    ('Toán cao cấp', 'Giải tích và đại số', true), ('Toán rời rạc', null, true),
    ('Nhập môn toán', null, true), ('Toam hoc', null, true), ('Giải tích 1', null, true),
    ('Toán (đã tắt)', null, false), ('Mã nguồn mở', null, true), ('Ma nguon mo', null, true),
    ('100% thực hành', null, true), ('100 câu hỏi', null, true),
    ('Đề thi Đại số tuyến tính', null, true), ('C++ cơ bản', null, true), ('C cơ bản', null, true),
    ('Xác suất', null, true);
insert into public.subjects (name, is_active) select 'Pagination môn ' || lpad(g::text, 2, '0'), true from generate_series(1, 45) g;
insert into public.subjects (name, is_active) select 'Window môn ' || lpad(g::text, 3, '0'), true from generate_series(1, 120) g;

insert into public.posts (id, author_id, type, title, content, created_at) values
    ('00000000-0000-4000-8000-000000003001', '00000000-0000-4000-8000-000000001001', 'question:public', 'Hỏi về giải tích', 'Cần giúp bài tập', now() - interval '12 min'),
    ('00000000-0000-4000-8000-000000003002', '00000000-0000-4000-8000-000000001001', 'knowledge:friends', 'Giải tích ghi chú bạn bè', null, now() - interval '11 min'),
    ('00000000-0000-4000-8000-000000003003', '00000000-0000-4000-8000-000000001001', 'none:private', 'Giải tích riêng tư', null, now() - interval '10 min'),
    ('00000000-0000-4000-8000-000000003004', '00000000-0000-4000-8000-000000001001', null, 'Legacy giải tích null', null, now() - interval '9 min'),
    ('00000000-0000-4000-8000-000000003005', '00000000-0000-4000-8000-000000001001', 'question', 'Legacy giải tích question', null, now() - interval '8 min'),
    ('00000000-0000-4000-8000-000000003006', '00000000-0000-4000-8000-000000001001', 'friends', 'Legacy giải tích friends', null, now() - interval '7 min'),
    ('00000000-0000-4000-8000-000000003007', '00000000-0000-4000-8000-000000001001', 'none:public', 'Bài khác', 'Hôm nay ôn lại giải tích chương 2', now() - interval '6 min'),
    ('00000000-0000-4000-8000-000000003008', '00000000-0000-4000-8000-000000001001', 'document:public', 'Tài liệu ôn thi Giải tích', null, now() - interval '5 min'),
    ('00000000-0000-4000-8000-000000003009', '00000000-0000-4000-8000-000000001001', 'document:public', 'Đề cương', null, now() - interval '4 min'),
    ('00000000-0000-4000-8000-000000003010', '00000000-0000-4000-8000-000000001006', 'none:public', 'B giải tích công khai', null, now() - interval '3 min'),
    ('00000000-0000-4000-8000-000000003011', '00000000-0000-4000-8000-000000001004', 'none:public', 'X giải tích công khai', null, now() - interval '2 min'),
    ('00000000-0000-4000-8000-000000003012', '00000000-0000-4000-8000-000000002150', 'none:friends', 'Ghi chú riêng bạn thứ 150', null, now() - interval '1 min');

insert into public.post_files (id, post_id, file_url, file_name, file_type) values
    ('00000000-0000-4000-8000-000000004001', '00000000-0000-4000-8000-000000003001', 'https://x/a', 'anh-bia.png', 'image/png'),
    ('00000000-0000-4000-8000-000000004002', '00000000-0000-4000-8000-000000003001', 'https://x/b', 'giai-tich-chuong-1.pdf', 'application/pdf'),
    ('00000000-0000-4000-8000-000000004003', '00000000-0000-4000-8000-000000003008', 'https://x/c', 'file1.docx', 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'),
    ('00000000-0000-4000-8000-000000004004', '00000000-0000-4000-8000-000000003003', 'https://x/d', 'giai_tich_private.pdf', 'application/pdf'),
    ('00000000-0000-4000-8000-000000004005', '00000000-0000-4000-8000-000000003009', 'https://x/e', 'De_thi_giai-tich.2023.pdf', 'application/pdf'),
    ('00000000-0000-4000-8000-000000004006', '00000000-0000-4000-8000-000000003001', 'https://x/f', 'scan-giai-tich.JPG', 'application/octet-stream');

-- Helpers (pg_temp is visible to the session regardless of the active role).
create function pg_temp.vals(j jsonb, k text) returns text[] language sql as $$
    select coalesce(array_agg(e ->> k order by ord), '{}') from jsonb_array_elements(j -> 'items') with ordinality t(e, ord)
$$;
create function pg_temp.has(j jsonb, k text, v text) returns boolean language sql as $$
    select v = any(pg_temp.vals(j, k))
$$;
create function pg_temp.uid(n integer) returns text language sql as $$
    select '00000000-0000-4000-8000-00000000' || n::text
$$;

-- ---------------------------------------------------------------- privileges
do $$
begin
    assert not has_function_privilege('anon', 'public.search_posts(text,integer,integer)', 'execute'), 'anon must not execute search_posts';
    assert has_function_privilege('authenticated', 'public.search_posts(text,integer,integer)', 'execute'), 'authenticated executes search_posts';
    assert not has_function_privilege('authenticated', 'studyhub_private.post_visibility(text)', 'execute'), 'visibility helper is private';
    assert not has_function_privilege('anon', 'studyhub_private.search_norm(text)', 'execute'), 'anon cannot run normalizer';
    assert not has_function_privilege('authenticated', 'studyhub_private.search_input(text,integer,integer)', 'execute'), 'input helper is private';
    assert (select prosecdef from pg_proc where oid = 'public.search_documents(text,integer,integer)'::regprocedure), 'RPC is security definer';
    assert (select proconfig @> array['search_path=""'] or proconfig @> array['search_path='] from pg_proc
            where oid = 'public.search_profiles(text,integer,integer)'::regprocedure), 'RPC has empty search_path';
    assert studyhub_private.post_visibility(null) = 'public' and studyhub_private.post_visibility('question') = 'public'
       and studyhub_private.post_visibility('friends') = 'friends' and studyhub_private.post_visibility(' Question : PRIVATE ') = 'private'
       and studyhub_private.post_visibility('none:weird') = 'public' and studyhub_private.post_visibility('private') = 'private',
       'post_visibility mirrors PostTypeUtils.parse';
    assert (select bool_and(studyhub_private.search_file_norm(v) = studyhub_private.search_norm(regexp_replace(v, '\.[A-Za-z0-9]{1,8}$', '')))
            from unnest(array['De_thi_giai-tich.2023.pdf', 'ĐỀ CƯƠNG.docx', 'a.b.c', 'noext', 'x.tar.gz']) v),
       'search_file_norm matches search_norm without the extension';
    raise notice 'PASS privileges and helpers';
end;
$$;

-- anon is denied by grants
set local role anon;
do $$
begin
    perform public.search_posts('giai tich');
    raise exception 'anon call unexpectedly succeeded';
exception when insufficient_privilege then
    raise notice 'PASS anon denied';
end;
$$;
reset role;

-- authenticated role without a session
select set_config('request.jwt.claims', '', true);
set local role authenticated;
do $$
begin
    perform public.search_subjects('toan');
    raise exception 'missing session unexpectedly succeeded';
exception when sqlstate 'F0005' then
    raise notice 'PASS missing session rejected';
end;
$$;
reset role;

-- banned viewer
select set_config('request.jwt.claims', '{"sub":"00000000-0000-4000-8000-000000001004","role":"authenticated"}', true);
set local role authenticated;
do $$
begin
    perform public.search_profiles('nguyen');
    raise exception 'banned viewer unexpectedly succeeded';
exception when sqlstate 'F0006' then
    raise notice 'PASS banned viewer rejected';
end;
$$;
reset role;

-- ---------------------------------------------------------------- viewer A
select set_config('request.jwt.claims', '{"sub":"00000000-0000-4000-8000-000000001001","role":"authenticated"}', true);
set local role authenticated;
do $$
declare r jsonb; n text[];
begin
    -- input validation
    begin perform public.search_subjects(repeat('a', 101)); raise exception 'oversized query accepted';
    exception when sqlstate 'F0001' then null; end;
    begin perform public.search_subjects('toan', 0, 0); raise exception 'limit 0 accepted';
    exception when sqlstate 'F0001' then null; end;
    begin perform public.search_subjects('toan', 20, -1); raise exception 'negative offset accepted';
    exception when sqlstate 'F0001' then null; end;
    begin perform public.search_subjects('toan', null, 0); raise exception 'null limit accepted';
    exception when sqlstate 'F0001' then null; end;
    perform public.search_subjects(repeat('a', 100));
    assert public.search_subjects('a') = '{"items":[],"has_more":false}'::jsonb, 'one-character query returns empty page';
    assert public.search_subjects('__') = '{"items":[],"has_more":false}'::jsonb, 'separator-only query returns empty page';
    raise notice 'PASS input validation';

    -- normalization
    assert pg_temp.has(public.search_subjects('toan cao cap'), 'name', 'Toán cao cấp'), 'unaccented query matches accented subject';
    assert pg_temp.has(public.search_subjects('DE THI DAI SO'), 'name', 'Đề thi Đại số tuyến tính'), 'case and đ normalization';
    assert pg_temp.has(public.search_subjects(E'tóan cao cấp'), 'name', 'Toán cao cấp'), 'NFD input is normalized';
    r := public.search_subjects('100%');
    assert pg_temp.has(r, 'name', '100% thực hành') and not pg_temp.has(r, 'name', '100 câu hỏi'), '% is literal';
    r := public.search_subjects('c++');
    assert pg_temp.has(r, 'name', 'C++ cơ bản') and not pg_temp.has(r, 'name', 'C cơ bản'), 'symbol query stays literal';
    raise notice 'PASS normalization';

    -- tiers and typo tolerance
    assert pg_temp.has(public.search_subjects('giai tihc'), 'name', 'Giải tích 1'), 'misspelled query matches';
    assert not pg_temp.has(public.search_subjects('xas'), 'name', 'Xác suất'), '3-character query does not use fuzzy matching';
    assert pg_temp.has(public.search_subjects('xacs'), 'name', 'Xác suất'), '4-character typo uses fuzzy matching';
    assert public.search_subjects('qwzv') -> 'items' = '[]'::jsonb, 'unrelated text is not matched';
    n := pg_temp.vals(public.search_subjects('toan'), 'name');
    assert array_position(n, 'Toán cao cấp') < array_position(n, 'Toán rời rạc')
       and array_position(n, 'Toán rời rạc') < array_position(n, 'Nhập môn toán')
       and array_position(n, 'Nhập môn toán') < array_position(n, 'Toam hoc'), 'tier ordering: ' || n::text;
    assert not ('Toán (đã tắt)' = any(n)), 'inactive subject excluded';
    n := pg_temp.vals(public.search_subjects('mã nguồn'), 'name');
    assert array_position(n, 'Mã nguồn mở') < array_position(n, 'Ma nguon mo'), 'exact diacritics rank first: ' || n::text;
    assert public.search_subjects('toan') = public.search_subjects('toan'), 'repeated search is stable';
    raise notice 'PASS tiers, typos and ordering';

    -- pagination
    r := public.search_subjects('pagination', 20, 0);
    assert jsonb_array_length(r -> 'items') = 20 and (r ->> 'has_more')::boolean, 'page 1';
    n := pg_temp.vals(r, 'id');
    r := public.search_subjects('pagination', 20, 20);
    assert jsonb_array_length(r -> 'items') = 20 and (r ->> 'has_more')::boolean, 'page 2';
    n := n || pg_temp.vals(r, 'id');
    r := public.search_subjects('pagination', 20, 40);
    assert jsonb_array_length(r -> 'items') = 5 and not (r ->> 'has_more')::boolean, 'page 3';
    n := n || pg_temp.vals(r, 'id');
    assert (select count(distinct x) from unnest(n) x) = 45, 'all 45 reachable once';
    r := public.search_subjects('window', 50, 80);
    assert jsonb_array_length(r -> 'items') = 20 and not (r ->> 'has_more')::boolean, 'window capped at 100';
    assert jsonb_array_length(public.search_subjects('window', 999, 0) -> 'items') = 50, 'page size capped at 50';
    assert public.search_subjects('window', 20, 100) = '{"items":[],"has_more":false}'::jsonb, 'offset beyond window';
    raise notice 'PASS pagination';

    -- users
    r := public.search_profiles('nguyen van');
    assert pg_temp.has(r, 'username', 'nguyenvana'), 'partial display name';
    assert not pg_temp.has(r, 'username', 'banned_x'), 'banned excluded';
    assert not pg_temp.has(r, 'username', 'blocker_b'), 'blocked by target excluded';
    assert not pg_temp.has(r, 'username', 'blocked_c'), 'blocked by viewer excluded';
    assert pg_temp.has(public.search_profiles('nguyen vna an'), 'username', 'nguyenvana'), 'misspelled name';
    assert pg_temp.has(public.search_profiles('author_a'), 'username', 'author_a'), 'self included';
    assert (select bool_and((select array_agg(k order by k) from jsonb_object_keys(e) k) = array['avatar_url','display_name','id','username'])
            from jsonb_array_elements(r -> 'items') e), 'profile projection';
    raise notice 'PASS users (viewer A)';

    -- posts: the author sees all own posts, never blocked or banned authors
    n := pg_temp.vals(public.search_posts('giai tich', 50, 0), 'id');
    assert n @> array[pg_temp.uid(3001), pg_temp.uid(3002), pg_temp.uid(3003), pg_temp.uid(3004),
                      pg_temp.uid(3005), pg_temp.uid(3006), pg_temp.uid(3007), pg_temp.uid(3008)], 'author sees own posts: ' || n::text;
    assert not (n && array[pg_temp.uid(3010), pg_temp.uid(3011), pg_temp.uid(3012)]), 'blocked/banned/unrelated excluded';
    n := pg_temp.vals(public.search_documents('giai tich', 50, 0), 'id');
    assert n @> array[pg_temp.uid(4002), pg_temp.uid(4003), pg_temp.uid(4004), pg_temp.uid(4005)], 'author documents';
    assert not (n && array[pg_temp.uid(4001), pg_temp.uid(4006)]), 'images excluded';
    raise notice 'PASS posts and documents (viewer A)';

    -- index expressions run with the writer's privileges
    insert into public.posts (id, author_id, type, title)
    values ('00000000-0000-4000-8000-000000003013', '00000000-0000-4000-8000-000000001001', 'none:public', 'Kiểm tra ghi giải tích');
    insert into public.post_files (post_id, file_url, file_name, file_type)
    values ('00000000-0000-4000-8000-000000003013', 'https://x/g', 'ghi-thu.pdf', 'application/pdf');
    raise notice 'PASS authenticated writes to indexed tables';
end;
$$;
reset role;

-- ---------------------------------------------------------------- viewer F (friend of A)
select set_config('request.jwt.claims', '{"sub":"00000000-0000-4000-8000-000000001002","role":"authenticated"}', true);
set local role authenticated;
do $$
declare r jsonb; n text[];
begin
    r := public.search_posts('giai tich', 50, 0);
    n := pg_temp.vals(r, 'id');
    assert n @> array[pg_temp.uid(3001), pg_temp.uid(3002), pg_temp.uid(3004), pg_temp.uid(3005),
                      pg_temp.uid(3006), pg_temp.uid(3007), pg_temp.uid(3008), pg_temp.uid(3010)], 'friend visibility: ' || n::text;
    assert not (n && array[pg_temp.uid(3003), pg_temp.uid(3011)]), 'friend cannot see private or banned';
    assert position(pg_temp.uid(3003) in r::text) = 0, 'private post absent from raw response';
    raise notice 'PASS posts (viewer F)';
end;
$$;
reset role;

-- ---------------------------------------------------------------- viewer S (stranger)
select set_config('request.jwt.claims', '{"sub":"00000000-0000-4000-8000-000000001003","role":"authenticated"}', true);
set local role authenticated;
do $$
declare r jsonb; n text[]; keys text[];
begin
    r := public.search_profiles('nguyen van');
    assert pg_temp.has(r, 'username', 'blocker_b') and pg_temp.has(r, 'username', 'blocked_c')
       and not pg_temp.has(r, 'username', 'banned_x'), 'stranger user visibility';

    r := public.search_posts('giai tich', 50, 0);
    n := pg_temp.vals(r, 'id');
    assert n @> array[pg_temp.uid(3001), pg_temp.uid(3004), pg_temp.uid(3005), pg_temp.uid(3007),
                      pg_temp.uid(3008), pg_temp.uid(3010), pg_temp.uid(3013)], 'stranger visibility: ' || n::text;
    assert not (n && array[pg_temp.uid(3002), pg_temp.uid(3003), pg_temp.uid(3006), pg_temp.uid(3011)]), 'stranger exclusions';
    assert position(pg_temp.uid(3002) in r::text) = 0 and position(pg_temp.uid(3003) in r::text) = 0, 'hidden posts absent from raw response';
    assert not pg_temp.has(public.search_posts('ban thu 150'), 'id', pg_temp.uid(3012)), 'stranger cannot see friends-only post';

    r := public.search_documents('giai tich', 50, 0);
    n := pg_temp.vals(r, 'id');
    assert n @> array[pg_temp.uid(4002), pg_temp.uid(4003), pg_temp.uid(4005)], 'stranger documents: ' || n::text;
    assert not (n && array[pg_temp.uid(4001), pg_temp.uid(4004), pg_temp.uid(4006)]), 'image/private files excluded';
    assert position(pg_temp.uid(4004) in r::text) = 0, 'private file absent from raw response';
    select array_agg(k order by k) into keys from jsonb_object_keys(r -> 'items' -> 0) k;
    assert keys = array['author_display_name','created_at','file_name','file_size','file_type','id','post_id','post_title'], 'document projection: ' || keys::text;
    assert pg_temp.has(public.search_documents('giai tich 2023'), 'file_name', 'De_thi_giai-tich.2023.pdf'), 'file name separators';
    raise notice 'PASS users, posts and documents (viewer S)';
end;
$$;
reset role;

-- ---------------------------------------------------------------- viewer M (150 friends)
select set_config('request.jwt.claims', '{"sub":"00000000-0000-4000-8000-000000001008","role":"authenticated"}', true);
set local role authenticated;
do $$
begin
    assert pg_temp.has(public.search_posts('ban thu 150'), 'id', pg_temp.uid(3012)), 'friend beyond the first hundred';
    raise notice 'PASS friend beyond the first hundred';
end;
$$;
reset role;

\echo 'ALL UNIFIED SEARCH TESTS PASSED'
rollback;
