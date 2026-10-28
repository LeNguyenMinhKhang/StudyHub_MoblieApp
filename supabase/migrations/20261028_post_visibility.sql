-- ==============================================================================
-- Migration: Hỗ trợ quyền riêng tư cho bài viết (Công khai, Bạn bè, Riêng tư)
-- Gỡ bỏ ràng buộc posts_type_check cũ để lưu trữ định dạng type kết hợp:
-- <thể loại>:<quyền riêng tư> (vd: question:public, knowledge:private, none:friends)
-- ==============================================================================

alter table public.posts drop constraint if exists posts_type_check;
