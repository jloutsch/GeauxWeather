/*
 * GeauxWeather
 * Copyright (C) 2026 TuneFriend / James
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

/**
 * Settings dropdowns in the app's own style instead of Android's grey picker dialog.
 * Each <select> stays in the page (hidden) as the source of truth: picking an option sets its value
 * and fires "change", so the existing settings code is untouched. Code that sets select.value
 * updates the button through a per-element value setter.
 */
(function (global) {
  'use strict';

  const doc = global.document;
  const valueDesc = Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value');
  let open = null; // { select, button, menu, backdrop }
  let ownBack = false; // the next popstate comes from close() undoing its own history entry
  let uid = 0;

  function selectedText(select) {
    const opt = select.options[select.selectedIndex];
    return opt ? opt.textContent : '';
  }

  function enhance(select) {
    if (select.dataset.gwMenu) return;
    select.dataset.gwMenu = '1';
    const row = select.closest('label');
    const labelEl = row && row.querySelector('span');
    const button = doc.createElement('button');
    button.type = 'button';
    button.className = 'gw-select';
    button.setAttribute('aria-haspopup', 'listbox');
    button.setAttribute('aria-expanded', 'false');
    const text = doc.createElement('span');
    text.className = 'gw-select-text';
    button.appendChild(text);
    button.insertAdjacentHTML(
      'beforeend',
      '<svg class="gw-select-chevron" viewBox="0 0 12 8" aria-hidden="true"><path d="M1 1.5l5 5 5-5" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/></svg>'
    );
    if (labelEl) {
      if (!labelEl.id) labelEl.id = 'gw-select-label-' + ++uid;
      text.id = 'gw-select-value-' + uid;
      button.setAttribute('aria-labelledby', labelEl.id + ' ' + text.id);
    }
    const update = () => {
      text.textContent = selectedText(select);
    };
    // Settings code assigns select.value directly (startup sync, unit defaults): keep the button in step
    Object.defineProperty(select, 'value', {
      configurable: true,
      get() {
        return valueDesc.get.call(this);
      },
      set(v) {
        valueDesc.set.call(this, v);
        update();
      },
    });
    select.addEventListener('change', update);
    select.classList.add('gw-native-select');
    select.setAttribute('aria-hidden', 'true');
    select.tabIndex = -1;
    select.insertAdjacentElement('afterend', button);
    update();

    button.addEventListener('click', (e) => {
      e.preventDefault(); // inside a <label>: don't also activate the hidden select
      if (open && open.select === select) close(true);
      else openMenu(select, button);
    });
    button.addEventListener('keydown', (e) => {
      if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
        e.preventDefault();
        openMenu(select, button);
      }
    });
  }

  function openMenu(select, button) {
    close(false);
    const backdrop = doc.createElement('div');
    backdrop.className = 'gw-menu-backdrop';
    const menu = doc.createElement('div');
    menu.className = 'gw-menu';
    menu.setAttribute('role', 'listbox');
    menu.setAttribute('aria-labelledby', button.getAttribute('aria-labelledby') || '');
    Array.from(select.options).forEach((opt, i) => {
      const item = doc.createElement('div');
      item.className = 'gw-option';
      item.setAttribute('role', 'option');
      item.tabIndex = -1;
      item.dataset.index = String(i);
      item.setAttribute('aria-selected', String(i === select.selectedIndex));
      item.innerHTML = '<span class="gw-option-text"></span><span class="gw-option-check" aria-hidden="true">✓</span>';
      item.firstChild.textContent = opt.textContent;
      menu.appendChild(item);
    });
    menu.addEventListener('click', (e) => {
      const item = e.target.closest('.gw-option');
      if (item) choose(Number(item.dataset.index));
    });
    menu.addEventListener('keydown', onMenuKey);
    backdrop.addEventListener('click', () => close(true));
    doc.body.appendChild(backdrop);
    doc.body.appendChild(menu);
    open = { select, button, menu, backdrop, pushed: false };
    button.setAttribute('aria-expanded', 'true');
    position(button, menu);
    const current = menu.children[select.selectedIndex] || menu.firstChild;
    if (current) current.focus({ preventScroll: true });
    // Android Back closes the menu instead of leaving the screen. If the previous menu's history entry
    // is still being removed, add ours when that finishes (popstate), or the browser drops it.
    if (!ownBack) pushMenuState();
  }

  function pushMenuState() {
    global.history.pushState({ gwMenu: true }, '');
    open.pushed = true;
  }

  /** Below the control (above if it doesn't fit), right-aligned to it; full width for stacked rows. */
  function position(button, menu) {
    const r = button.getBoundingClientRect();
    const vw = doc.documentElement.clientWidth;
    const vh = doc.documentElement.clientHeight;
    const gutter = 12;
    menu.style.minWidth = Math.max(r.width, 160) + 'px';
    menu.style.maxWidth = vw - gutter * 2 + 'px';
    const h = menu.offsetHeight;
    const w = menu.offsetWidth;
    const below = vh - r.bottom - gutter;
    const above = r.top - gutter;
    let top = below >= h || below >= above ? r.bottom + 6 : r.top - 6 - Math.min(h, above);
    menu.style.maxHeight = Math.max(below >= h || below >= above ? below : above, 120) + 'px';
    let left = Math.min(r.right - w, vw - gutter - w);
    left = Math.max(gutter, left);
    menu.style.top = Math.max(gutter, top) + 'px';
    menu.style.left = left + 'px';
  }

  function onMenuKey(e) {
    const items = Array.from(open.menu.children);
    const i = items.indexOf(doc.activeElement);
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault();
      const n = Math.min(items.length - 1, Math.max(0, i + (e.key === 'ArrowDown' ? 1 : -1)));
      items[n].focus();
    } else if (e.key === 'Home' || e.key === 'End') {
      e.preventDefault();
      items[e.key === 'Home' ? 0 : items.length - 1].focus();
    } else if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      if (i >= 0) choose(i);
    } else if (e.key === 'Escape' || e.key === 'Tab') {
      e.preventDefault();
      close(true);
    }
  }

  function choose(index) {
    const { select } = open;
    const changed = index !== select.selectedIndex;
    close(true);
    if (!changed) return;
    select.selectedIndex = index;
    select.dispatchEvent(new Event('change', { bubbles: true }));
  }

  /** @param {boolean} focusButton return focus to the control (not when another menu is opening) */
  function close(focusButton) {
    if (!open) return;
    const { button, menu, backdrop, pushed } = open;
    open = null;
    menu.remove();
    backdrop.remove();
    button.setAttribute('aria-expanded', 'false');
    if (focusButton) button.focus();
    if (pushed && global.history.state && global.history.state.gwMenu) {
      ownBack = true;
      global.history.back();
    }
  }

  // Android Back (MainActivity asks before its default action): true = a menu was open and is now closed
  global.__geauxBack = function () {
    if (!open) return false;
    close(true);
    return true;
  };

  global.addEventListener('popstate', () => {
    if (ownBack) {
      ownBack = false; // our own cleanup; a menu opened meanwhile stays open and now gets its entry
      if (open && !open.pushed) pushMenuState();
      return;
    }
    close(true); // Back button
  });
  global.addEventListener('resize', () => close(false));
  doc.addEventListener('scroll', (e) => open && !open.menu.contains(e.target) && close(false), true);

  doc.querySelectorAll('.setting-row select').forEach(enhance);
})(window);
