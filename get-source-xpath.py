import hashlib
import json
import sys
import os

do_counting = 'COUNTING' in os.environ
reduce_attrs = ['idn', 'id', 'class', 'text', 'cdesc', None, 'text', None]


def find_source_path(ui):
    if 'is_source' in ui:
        return [-1]
    chs = ui.get('ch')
    if not chs:
        return None
    for i in range(len(chs)):
        ch = chs[i]
        r = find_source_path(ch)
        if r:
            r.append(i)
            return r
    return None


def gather_child_attr(root, attr):
    ret = set()
    my_attr = root.get(attr)
    if my_attr:
        ret.add(my_attr)
    chs = root.get('ch')
    if chs:
        for ch in chs:
            ret.update(gather_child_attr(ch, attr))
    return ret


def sorted_gather_child_attr(root, attr):
    return list(sorted(gather_child_attr(root, attr)))


def build_xpath(ui, indices):
    pos = indices[-1]
    if pos < 0:
        return []
    attr_vals = {}
    candidates = ui['ch']
    target = candidates[pos]
    is_ch_attr = False
    for attr in reduce_attrs:
        if len(candidates) <= 1:
            break
        if not attr:
            if len(indices) == 2 and not is_ch_attr:
                # See if child attrs help break the tie
                is_ch_attr = True
                continue
            attr_vals['_pos'] = pos
            break
        if is_ch_attr:
            v = sorted_gather_child_attr(target, attr)
            attr_vals["ch_" + attr] = v
            candidates = [e for e in candidates if sorted_gather_child_attr(e, attr) == v]
        elif attr in target:
            attr_vals[attr] = target[attr]
            candidates = [e for e in candidates if e.get(attr) == target[attr]]
    r = build_xpath(target, indices[:-1])
    r.append(attr_vals)
    return r


def parse_pos(pos):
    pos_c1 = pos.find(",")
    left = int(pos[1: pos_c1])
    pos_b1 = pos.find("]")
    top = int(pos[pos_c1 + 1: pos_b1])
    pos_c2 = pos.find(",", pos_b1)
    right = int(pos[pos_b1 + 2: pos_c2])
    bottom = int(pos[pos_c2 + 1: -1])
    return top, left, bottom - top, right - left


def hash_layout(layout):
    sanity_check_ok = True
    for field in ["bound", "act_id"]:
        if field not in layout:
            sanity_check_ok = False
            break
    if layout["act_id"] == "unknown" and not layout.get("focus", False):
        sanity_check_ok = False
    if not sanity_check_ok:
        print(layout)
        return None
    (s_t, s_l, s_h, s_w) = parse_pos(layout["bound"])
    check_elem_pos = s_h > 0 and s_w > 0
    if "vis" in layout:
        del layout["vis"]

    def traverse(layout, pool):
        if not layout:
            return None
        if "vis" in layout and layout["vis"] != 0:
            return None
        if "bound" not in layout:
            return None
        if check_elem_pos:
            (t, l, h, w) = parse_pos(layout["bound"])
            if t >= s_t + s_h or l >= s_l + s_w or s_t >= t + h or s_l >= l + w:
                return None
        pool.append("[")
        pool.append(str(layout.get("id", "-1")))
        pool.append(str(layout["class"]))
        if "ch" in layout:
            for ch in layout["ch"]:
                traverse(ch, pool)
        pool.append("]")

    ret = [layout.get("act_id", '?')]
    traverse(layout, ret)
    return hashlib.md5("".join(ret).encode()).hexdigest()


def process(fp):
    if not fp: return None

    def remove_blank(root):
        if "ch" in root:
            new_chs = []
            for ch in root["ch"]:
                if "hash" in ch:
                    remove_blank(ch)
                    new_chs.append(ch)
            root["ch"] = new_chs

    ret_hash = False
    if fp[0] == '!':
        fp = fp[1:]
        ret_hash = True
    with open(fp) as f:
        root_ui = json.load(f)
        remove_blank(root_ui)

    if ret_hash:
        return hash_layout(root_ui)

    path = find_source_path(root_ui)
    if not path:
        # print('Source UI element not found')
        return hash_layout(root_ui)
    # print(path)

    output = build_xpath(root_ui, path)
    # Fix old traces
    for attrs in output:
        if 'id' in attrs and type(attrs['id']) is int:
            attrs['idn'] = attrs['id']
            del attrs['id']
    # Add activity ID
    act_id = root_ui.get('act_id')
    if not act_id:
        return None
    output.append({'act_id': act_id})
    return list(reversed(output))


ret_out = []
xpath_ct = {}
xpath_first_occ = {}
for fp in sys.argv[1:]:
    xp = process(fp)
    if xp:
        ret_out.append(xp)
        if not do_counting: continue
        xp = str(xp)
        if xp in xpath_ct: xpath_ct[xp] += 1
        else:
            xpath_ct[xp] = 1
            xpath_first_occ[xp] = fp
if do_counting:
    for xp, ct in sorted(xpath_ct.items(), key=lambda x: -x[1]):
        print(xpath_first_occ[xp], ct)
else: print(json.dumps(ret_out, separators=(',', ':')))
