-- KloudStream's player controls, drawn like a streaming app's: dark gradients behind them, a rounded
-- timeline with a knob, round buttons with French tooltips, a pause flash and a loading ring.
-- uosc stays loaded for its menus only (its own elements are disabled in uosc.conf).
local assdraw = require 'mp.assdraw'
local utils = require 'mp.utils'

local FONT, ICONS = 'Segoe UI', 'MaterialIconsRound-Regular'
local RED, WHITE, BLACK, PANEL = 'E50914', 'FFFFFF', '000000', '242424'
local HIDE_AFTER, FADE = 2.5, 0.15 -- seconds

local LANGUAGES = {
    fr = 'Français', fre = 'Français', fra = 'Français', en = 'Anglais', eng = 'Anglais',
    es = 'Espagnol', spa = 'Espagnol', de = 'Allemand', ger = 'Allemand', deu = 'Allemand',
    it = 'Italien', ita = 'Italien', pt = 'Portugais', por = 'Portugais', ja = 'Japonais', jpn = 'Japonais',
    ko = 'Coréen', kor = 'Coréen', zh = 'Chinois', chi = 'Chinois', zho = 'Chinois', ar = 'Arabe', ara = 'Arabe',
    ru = 'Russe', rus = 'Russe', nl = 'Néerlandais', dut = 'Néerlandais', nld = 'Néerlandais',
    hi = 'Hindi', hin = 'Hindi', tr = 'Turc', tur = 'Turc', pl = 'Polonais', pol = 'Polonais',
}
local CHANNELS = {[1] = 'Mono', [2] = 'Stéréo', [6] = '5.1', [8] = '7.1'}

local state = {
    w = 0, h = 0, s = 1, mouse = {x = -1, y = -1}, inside = false,
    shown = false, opacity = 0, menu = nil, idle = true, loading_since = nil,
    position = nil, duration = nil, paused = false, cache = {},
    volume = 100, mute = false, fullscreen = false, speed = 1,
    title = '', episode = nil, tracks = {sub = {}, audio = {}}, sid = nil, aid = nil, next_episode = false,
    drag = nil, drag_time = nil, pressed = nil, seek_at = 0, flash = nil, no_flash_until = 0,
}
local overlay = mp.create_osd_overlay('ass-events')
local buttons, geometry = {}, {}
local last_frame, render_timer, hide_timer = 0, nil, nil
local request
local base_sub_margin = mp.get_property_number('sub-margin-y', 22)

-- Drawing ---------------------------------------------------------------------------------------

local function bgr(rgb) return rgb:sub(5, 6) .. rgb:sub(3, 4) .. rgb:sub(1, 2) end
local function alpha(opacity)
    return string.format('%02X', math.floor(255 * (1 - math.max(0, math.min(1, opacity))) + 0.5))
end
local function escape(value)
    return (value:gsub('\\', '\\\239\187\191'):gsub('{', '\\{'):gsub('^ ', '\\h'):gsub('\n', '\\N'))
end

local function fill(ass, rgb, opacity, draw, blur)
    ass:new_event()
    ass:append(string.format('{\\an7\\pos(0,0)\\bord0\\shad0\\blur%.1f\\1c&H%s&\\1a&H%s&}', blur or 0, bgr(rgb), alpha(opacity)))
    ass:draw_start()
    draw(ass)
    ass:draw_stop()
end

local function box(ass, rgb, opacity, x0, y0, x1, y1, radius, blur)
    if x1 - x0 < 0.5 or y1 - y0 < 0.5 or opacity <= 0 then return end
    radius = math.min(radius or 0, (x1 - x0) / 2, (y1 - y0) / 2)
    fill(ass, rgb, opacity, function(a) a:round_rect_cw(x0, y0, x1, y1, radius) end, blur)
end

local function circle(ass, rgb, opacity, x, y, radius) box(ass, rgb, opacity, x - radius, y - radius, x + radius, y + radius, radius) end

local function text(ass, x, y, align, size, value, opts)
    opts = opts or {}
    local opacity = opts.opacity or 1
    ass:new_event()
    ass:append(string.format('{\\an%d\\pos(%.1f,%.1f)\\fn%s\\fs%.1f\\b%d\\bord0\\shad0\\1c&H%s&\\1a&H%s&}',
        align, x, y, opts.font or FONT, size, opts.weight or 400, bgr(opts.color or WHITE), alpha(opacity)))
    ass:append(opts.raw and value or escape(value))
end

local function icon(ass, x, y, size, name, opacity)
    text(ass, x, y, 5, size, name, {font = ICONS, opacity = opacity, raw = true})
end

-- Code points times an average Segoe UI advance: enough to size a tooltip.
local function text_width(value, size) return select(2, value:gsub('[^\128-\191]', '')) * size * 0.54 end

-- Blurred black layers of one small opacity stacked from the screen edge, reaching less and less far:
-- a smooth gradient, eased (opacity × t^2.5) to stay light where the subtitles are and dark behind
-- the controls. The OSD covers the subtitles, so they would turn grey under a darker one.
local function scrim(ass, edge, depth, darkest, fade)
    local layers = 24
    local step = 1 - (1 - darkest) ^ (1 / layers)
    local blur = depth / 10
    local margin = 3 * blur
    for i = 1, layers do
        local t = ((1 - (1 - step) ^ (i - 0.5)) / darkest) ^ (1 / 2.5)
        local reach = depth * (1 - t)
        if edge == 'top' then box(ass, BLACK, step * fade, -margin, -margin, state.w + margin, reach, 0, blur)
        else box(ass, BLACK, step * fade, -margin, state.h - reach, state.w + margin, state.h + margin, 0, blur) end
    end
end

local function ring(ass, x, y, radius, thickness, from, sweep, rgb, opacity)
    fill(ass, rgb, opacity, function(a)
        local steps = 48
        for i = 0, steps do
            local t = from + sweep * i / steps
            local px, py = x + radius * math.cos(t), y + radius * math.sin(t)
            if i == 0 then a:move_to(px, py) else a:line_to(px, py) end
        end
        for i = steps, 0, -1 do
            local t = from + sweep * i / steps
            a:line_to(x + (radius - thickness) * math.cos(t), y + (radius - thickness) * math.sin(t))
        end
    end)
end

local function clock(seconds)
    seconds = math.max(0, math.floor(seconds or 0))
    local h, m, s = math.floor(seconds / 3600), math.floor(seconds / 60) % 60, seconds % 60
    if h > 0 then return string.format('%d:%02d:%02d', h, m, s) end
    return string.format('%d:%02d', m, s)
end

-- Actions -----------------------------------------------------------------------------------------

local function language(track)
    return track.lang and (LANGUAGES[track.lang:lower()] or track.lang) or nil
end

local function open_menu(menu_type, title, items)
    mp.commandv('script-message-to', 'uosc', 'open-menu', utils.format_json({type = menu_type, title = title, items = items}))
end

local function track_items(kind, selected)
    local items = {}
    if kind == 'sub' then items[1] = {title = 'Désactivés', value = {'set', 'sid', 'no'}, active = selected == nil} end
    for _, track in ipairs(state.tracks[kind]) do
        local name = language(track)
        local title = track.title or name or ('Piste ' .. track.id)
        local hints = {}
        if name and not title:lower():find(name:lower(), 1, true) then hints[#hints + 1] = name end
        if track.forced then hints[#hints + 1] = 'forcés' end
        if kind == 'audio' then hints[#hints + 1] = CHANNELS[track['demux-channel-count']] end
        items[#items + 1] = {
            title = title, hint = #hints > 0 and table.concat(hints, ' · ') or nil, active = track.id == selected,
            value = {'set', kind == 'sub' and 'sid' or 'aid', tostring(track.id)},
        }
    end
    if #items == 1 and kind == 'sub' then
        items[2] = {title = 'Aucun sous-titre pour cette source', muted = true, italic = true, selectable = false, value = 'ignore'}
    end
    return items
end

local function open_subtitles() open_menu('kloudstream-subtitles', 'Sous-titres', track_items('sub', state.sid)) end
local function open_audio() open_menu('kloudstream-audio', 'Audio', track_items('audio', state.aid)) end
local function open_speed()
    local items = {}
    for _, speed in ipairs({0.5, 0.75, 1, 1.25, 1.5, 2}) do
        items[#items + 1] = {
            title = speed == 1 and 'Normale' or (tostring(speed):gsub('%.', ',') .. '×'),
            value = {'set', 'speed', tostring(speed)}, active = math.abs(state.speed - speed) < 0.01,
        }
    end
    open_menu('kloudstream-speed', 'Vitesse', items)
end
-- Handled by the app, which knows the next episode (PlaybackController).
local function next_episode() mp.commandv('script-message', 'kloudstream-next-episode') end

-- Layout --------------------------------------------------------------------------------------------

local function volume_icon()
    if state.mute or state.volume <= 0 then return 'volume_off' end
    return state.volume < 50 and 'volume_down' or 'volume_up'
end

local function layout()
    local s, w, h = state.s, state.w, state.h
    local pad = 56 * s
    geometry = {pad = pad, bar_y = h - 138 * s, row_y = h - 62 * s, top_y = 60 * s, bar_x0 = pad, bar_x1 = w - pad}
    buttons = {}
    local function add(id, x, y, name, size, tip, action)
        buttons[#buttons + 1] = {id = id, x = x, y = y, r = 28 * s, icon = name, size = size * s, tip = tip, action = action}
    end
    local y, step = geometry.row_y, 64 * s
    local x = pad + 18 * s
    add('play', x, y, state.paused and 'play_arrow' or 'pause', 54, state.paused and 'Lecture' or 'Pause',
        function() mp.commandv('cycle', 'pause') end)
    x = x + step + 4 * s
    add('back', x, y, 'replay_10', 40, 'Reculer de 10 s', function() mp.commandv('seek', -10) end)
    x = x + step
    add('forward', x, y, 'forward_10', 40, 'Avancer de 10 s', function() mp.commandv('seek', 10) end)
    x = x + step
    add('mute', x, y, volume_icon(), 38, state.mute and 'Réactiver le son' or 'Couper le son',
        function() mp.commandv('cycle', 'mute') end)
    geometry.volume = {x0 = x + 34 * s, x1 = x + 130 * s, y = y}
    geometry.time_x = geometry.volume.x1 + 32 * s

    x = w - pad - 18 * s
    add('fullscreen', x, y, state.fullscreen and 'fullscreen_exit' or 'fullscreen', 44,
        state.fullscreen and 'Quitter le plein écran' or 'Plein écran', function() mp.commandv('cycle', 'fullscreen') end)
    x = x - step
    add('speed', x, y, 'speed', 38, 'Vitesse', open_speed)
    if #state.tracks.audio > 1 then x = x - step; add('audio', x, y, 'audiotrack', 36, 'Audio', open_audio) end
    -- Always there: the app may add French subtitles after the start, and "none" is an answer too.
    x = x - step
    add('subtitles', x, y, state.sid and 'subtitles' or 'subtitles_off', 38, 'Sous-titres', open_subtitles)
    if state.next_episode then x = x - step; add('next', x, y, 'skip_next', 44, 'Épisode suivant', next_episode) end
    add('close', pad + 18 * s, geometry.top_y, 'arrow_back', 40, 'Retour', function() mp.commandv('quit') end)
end

local function over_timeline(x, y)
    local g = geometry
    return g.bar_y and y >= g.bar_y - 20 * state.s and y <= g.bar_y + 20 * state.s
        and x >= g.bar_x0 - 10 * state.s and x <= g.bar_x1 + 10 * state.s
end

local function over_volume(x, y)
    local v = geometry.volume
    return v and y >= v.y - 18 * state.s and y <= v.y + 18 * state.s and x >= v.x0 - 8 * state.s and x <= v.x1 + 8 * state.s
end

local function button_at(x, y)
    for _, b in ipairs(buttons) do
        if (x - b.x) ^ 2 + (y - b.y) ^ 2 <= b.r ^ 2 then return b end
    end
end

-- What the pointer is on, when the controls are shown.
local function hit(x, y)
    if not state.shown or state.opacity < 0.3 then return nil end
    local b = button_at(x, y)
    if b then return 'button', b end
    if over_timeline(x, y) then return 'timeline' end
    if over_volume(x, y) then return 'volume' end
end

local function time_at(x)
    local g = geometry
    return (state.duration or 0) * math.max(0, math.min(1, (x - g.bar_x0) / (g.bar_x1 - g.bar_x0)))
end

-- Rendering --------------------------------------------------------------------------------------

local function tooltip(ass, x, y, label, opacity, below)
    local s = state.s
    local size, height = 20 * s, 36 * s
    local width = text_width(label, size) + 30 * s
    x = math.max(width / 2 + 12 * s, math.min(state.w - width / 2 - 12 * s, x))
    local top = below and y or y - height
    box(ass, PANEL, 0.95 * opacity, x - width / 2, top, x + width / 2, top + height, height / 2)
    text(ass, x, top + height / 2, 5, size, label, {opacity = opacity, weight = 600})
end

local function draw_top(ass, o)
    local s, g = state.s, geometry
    local x = g.pad + 66 * s
    if state.episode then
        text(ass, x, g.top_y - 15 * s, 4, 30 * s, state.title, {weight = 600, opacity = o})
        text(ass, x, g.top_y + 18 * s, 4, 22 * s, state.episode, {opacity = 0.75 * o})
    else
        text(ass, x, g.top_y, 4, 30 * s, state.title, {weight = 600, opacity = o})
    end
end

local function draw_timeline(ass, o)
    local s, g = state.s, geometry
    local hot = state.drag == 'timeline' or (state.inside and not state.drag and over_timeline(state.mouse.x, state.mouse.y))
    local thick = (hot and 10 or 6) * s
    local y0, y1, round = g.bar_y - thick / 2, g.bar_y + thick / 2, thick / 2
    box(ass, WHITE, 0.24 * o, g.bar_x0, y0, g.bar_x1, y1, round)
    if not state.duration or state.duration <= 0 then return end
    local function at(t) return g.bar_x0 + (g.bar_x1 - g.bar_x0) * math.max(0, math.min(1, t / state.duration)) end
    for _, range in ipairs(state.cache) do
        box(ass, WHITE, 0.22 * o, at(range['start']), y0, at(range['end']), y1, round)
    end
    local played = at(state.drag == 'timeline' and state.drag_time or state.position or 0)
    local mx = math.max(g.bar_x0, math.min(g.bar_x1, state.mouse.x))
    if hot and mx > played then box(ass, WHITE, 0.35 * o, played - round, y0, mx, y1, round) end
    box(ass, RED, o, g.bar_x0, y0, played, y1, round)
    circle(ass, RED, o, played, g.bar_y, (hot and 11 or 8) * s)
    if hot then tooltip(ass, mx, g.bar_y - 22 * s, clock(time_at(mx)), o) end
end

local function draw_controls(ass, o)
    local s, g = state.s, geometry
    local hovered = state.inside and not state.drag and button_at(state.mouse.x, state.mouse.y)
    for _, b in ipairs(buttons) do
        local hot = hovered == b
        if hot then circle(ass, WHITE, 0.16 * o, b.x, b.y, 27 * s) end
        icon(ass, b.x, b.y, b.size * (hot and 1.08 or 1), b.icon, (hot and 1 or 0.92) * o)
    end
    -- Volume slider.
    local v = geometry.volume
    local hot = state.drag == 'volume' or (state.inside and not state.drag and over_volume(state.mouse.x, state.mouse.y))
    local level = state.mute and 0 or math.max(0, math.min(1, state.volume / 100))
    local thick = (hot and 6 or 4) * s
    local knob = v.x0 + (v.x1 - v.x0) * level
    box(ass, WHITE, 0.3 * o, v.x0, v.y - thick / 2, v.x1, v.y + thick / 2, thick / 2)
    box(ass, WHITE, o, v.x0, v.y - thick / 2, knob, v.y + thick / 2, thick / 2)
    circle(ass, WHITE, o, knob, v.y, (hot and 8 or 6) * s)
    -- Position / duration.
    if state.duration and state.duration > 0 then
        local position = state.drag == 'timeline' and state.drag_time or state.position
        text(ass, g.time_x, v.y, 4, 23 * s, string.format('%s{\\1a&H%s&}  /  %s', clock(position), alpha(0.6 * o), clock(state.duration)),
            {opacity = o, raw = true, weight = 600})
    end
    if hovered then
        local below = hovered.y < state.h / 2
        tooltip(ass, hovered.x, hovered.y + (below and 34 or -33) * s, hovered.tip, o, below)
    end
end

local function draw_flash(ass, now)
    local flash = state.flash
    if not flash then return false end
    local t = (now - flash.at) / 0.6
    if t >= 1 then state.flash = nil; return false end
    local grow, fade = 1 + 0.2 * t, 1 - t * t
    circle(ass, BLACK, 0.5 * fade, state.w / 2, state.h / 2, 60 * state.s * grow)
    icon(ass, state.w / 2, state.h / 2, 72 * state.s * grow, flash.icon, fade)
    return true
end

local function draw_loading(ass, now)
    if not state.loading_since or now - state.loading_since < 0.4 then return state.loading_since ~= nil end
    local s, x, y = state.s, state.w / 2, state.h / 2
    ring(ass, x, y, 34 * s, 5 * s, 0, 2 * math.pi, WHITE, 0.18)
    ring(ass, x, y, 34 * s, 5 * s, (now * 2 * math.pi * 1.1) % (2 * math.pi), math.pi * 0.6, RED, 1)
    return true
end

local function render()
    if state.w <= 0 then return end
    local now = mp.get_time()
    local dt = math.min(0.1, now - last_frame)
    last_frame = now
    local target = (state.shown and not state.menu) and 1 or 0
    if state.opacity < target then state.opacity = math.min(target, state.opacity + dt / FADE)
    elseif state.opacity > target then state.opacity = math.max(target, state.opacity - dt / FADE) end
    local animating = state.opacity ~= target
    local ass = assdraw.ass_new()
    local o = state.opacity
    if o > 0 then
        layout()
        scrim(ass, 'bottom', 320 * state.s, 0.85, o)
        scrim(ass, 'top', 180 * state.s, 0.65, o)
        draw_top(ass, o)
        draw_timeline(ass, o)
        draw_controls(ass, o)
    end
    animating = draw_flash(ass, now) or animating
    animating = draw_loading(ass, now) or animating
    overlay.res_x, overlay.res_y = state.w, state.h
    overlay.data = ass.text
    overlay:update()
    if animating then request() end
end

request = function()
    if render_timer then return end
    render_timer = mp.add_timeout(math.max(0, last_frame + 1 / 50 - mp.get_time()), function()
        render_timer = nil
        render()
    end)
end

-- Visibility -------------------------------------------------------------------------------------

local function over_controls()
    local y = state.mouse.y
    return state.inside and (y >= (geometry.bar_y or state.h) - 40 * state.s or y <= 110 * state.s)
end

local function set_shown(shown)
    if state.shown == shown then return end
    state.shown = shown
    -- Text subtitles move above the controls while they are shown.
    mp.set_property_number('sub-margin-y', shown and base_sub_margin + 110 or base_sub_margin)
    request()
end

local function show()
    set_shown(true)
    if hide_timer then hide_timer:kill() end
    hide_timer = mp.add_timeout(HIDE_AFTER, function()
        if state.paused or state.drag or over_controls() then show() else set_shown(false) end
    end)
    request()
end

-- Mouse ---------------------------------------------------------------------------------------------

local function drag_to(x)
    if state.drag == 'timeline' and state.duration then
        state.drag_time = time_at(x)
        local now = mp.get_time()
        if now - state.seek_at > 0.08 then
            state.seek_at = now
            mp.commandv('seek', state.drag_time, 'absolute+keyframes')
        end
    elseif state.drag == 'volume' then
        local v = geometry.volume
        local level = math.max(0, math.min(1, (x - v.x0) / (v.x1 - v.x0)))
        mp.set_property_number('volume', math.floor(level * 100 + 0.5))
        if state.mute and level > 0 then mp.set_property_bool('mute', false) end
    end
    request()
end

local function press()
    local x, y = state.mouse.x, state.mouse.y
    local kind, b = hit(x, y)
    if kind == 'button' then state.pressed = b.id
    elseif kind then state.drag = kind; drag_to(x)
    else mp.commandv('cycle', 'pause') end -- A click on the picture, as in streaming apps.
    show()
end

local function release()
    local x, y = state.mouse.x, state.mouse.y
    if state.drag == 'timeline' and state.drag_time then mp.commandv('seek', state.drag_time, 'absolute+exact') end
    local pressed = state.pressed
    state.drag, state.drag_time, state.pressed = nil, nil, nil
    if pressed then
        local kind, b = hit(x, y)
        if kind == 'button' and b.id == pressed then b.action() end
    end
    show()
end

local function on_click(event)
    if event.event == 'down' then press()
    elseif event.event == 'up' then release()
    elseif event.event == 'press' then press(); release() end
end

-- Both clicks paused and resumed: a double click on the picture only switches full screen.
local function on_double_click()
    if hit(state.mouse.x, state.mouse.y) then return end
    state.flash, state.no_flash_until = nil, mp.get_time() + 0.4
    mp.commandv('cycle', 'fullscreen')
end

-- uosc's menus take the mouse while they are open.
local function bind_mouse(enabled)
    if enabled then
        mp.add_forced_key_binding('mbtn_left', 'click', on_click, {complex = true})
        mp.add_forced_key_binding('mbtn_left_dbl', 'double-click', on_double_click)
    else
        mp.remove_key_binding('click')
        mp.remove_key_binding('double-click')
    end
end

-- Properties ------------------------------------------------------------------------------------

local function update_loading()
    local loading = state.idle and not state.paused
    if loading and not state.loading_since then state.loading_since = mp.get_time()
    elseif not loading then state.loading_since = nil end
    request()
end

mp.observe_property('osd-dimensions', 'native', function(_, d)
    if not d or not d.w or d.w <= 0 then return end
    state.w, state.h, state.s = d.w, d.h, math.max(0.6, d.h / 1080)
    request()
end)
mp.observe_property('mouse-pos', 'native', function(_, pos)
    if not pos then return end
    local moved = pos.x ~= state.mouse.x or pos.y ~= state.mouse.y
    state.mouse = {x = pos.x, y = pos.y}
    state.inside = pos.hover or moved
    if state.drag then drag_to(pos.x) end
    if moved then show()
    elseif not state.inside and not state.paused then set_shown(false) end
    request()
end)
mp.observe_property('time-pos', 'number', function(_, value)
    state.position = value
    if state.opacity > 0 then request() end
end)
mp.observe_property('duration', 'number', function(_, value) state.duration = value; request() end)
mp.observe_property('demuxer-cache-state', 'native', function(_, cache)
    state.cache = cache and cache['seekable-ranges'] or {}
    if state.opacity > 0 then request() end
end)
mp.observe_property('pause', 'bool', function(_, paused)
    local changed = state.paused ~= paused
    state.paused = paused
    if changed and state.duration and mp.get_time() > state.no_flash_until then
        state.flash = {icon = paused and 'pause' or 'play_arrow', at = mp.get_time()}
    end
    update_loading()
    show()
end)
mp.observe_property('core-idle', 'bool', function(_, idle) state.idle = idle; update_loading() end)
mp.observe_property('volume', 'number', function(_, value) state.volume = value or 100; request() end)
mp.observe_property('mute', 'bool', function(_, value) state.mute = value; request() end)
mp.observe_property('fullscreen', 'bool', function(_, value) state.fullscreen = value; request() end)
mp.observe_property('speed', 'number', function(_, value) state.speed = value or 1; request() end)
-- The app names the player "Title — Episode".
mp.observe_property('media-title', 'string', function(_, value)
    value = value or ''
    local cut = value:find(' — ', 1, true)
    state.title = cut and value:sub(1, cut - 1) or value
    state.episode = cut and value:sub(cut + #' — ') or nil
    request()
end)
mp.observe_property('track-list', 'native', function(_, tracks)
    state.tracks, state.sid, state.aid = {sub = {}, audio = {}}, nil, nil
    for _, track in ipairs(tracks or {}) do
        local list = state.tracks[track.type]
        if list then list[#list + 1] = track end
        if track.selected and track.type == 'sub' then state.sid = track.id end
        if track.selected and track.type == 'audio' then state.aid = track.id end
    end
    request()
end)
mp.observe_property('user-data/kloudstream/next-episode', 'native', function(_, value)
    state.next_episode = value == true
    request()
end)
mp.observe_property('user-data/uosc/menu/type', 'native', function(_, menu)
    state.menu = menu
    bind_mouse(menu == nil)
    request()
end)
-- Keyboard seeks show where they landed.
mp.register_event('seek', function() if state.duration then show() end end)

mp.add_key_binding(nil, 'subtitles', open_subtitles)
mp.add_key_binding(nil, 'audio', open_audio)
mp.add_key_binding(nil, 'speed', open_speed)
