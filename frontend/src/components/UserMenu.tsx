import { useEffect, useRef, useState, type KeyboardEvent } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';

export function initialsOf(name: string): string {
  const words = name.trim().split(/\s+/).filter(Boolean);
  const letters = words.length > 1 ? words[0][0] + words[words.length - 1][0] : (words[0] ?? '?').slice(0, 2);
  return letters.toUpperCase();
}

/**
 * The signed-in user's one menu: initials and first name on a button; it opens Account, My bookings, Admin (admins only) and Sign out.
 * A menu button pattern: aria-expanded, arrow keys, Home and End, Escape closes and returns the focus, a click outside closes.
 */
export function UserMenu() {
  const { user, hasRole, logout } = useAuth();
  const { pathname } = useLocation();
  const [open, setOpen] = useState(false);
  const button = useRef<HTMLButtonElement>(null);
  const menu = useRef<HTMLDivElement>(null);

  const displayName = user?.name ?? user?.email ?? 'Account';
  const firstName = user?.name ? user.name.split(/\s+/)[0] : (user?.email?.split('@')[0] ?? 'Account');

  const items = () => Array.from(menu.current?.querySelectorAll<HTMLElement>('[role="menuitem"]') ?? []);

  // Opening puts the focus on the first item; a route change closes the menu.
  useEffect(() => {
    if (open) items()[0]?.focus();
  }, [open]);
  useEffect(() => setOpen(false), [pathname]);

  useEffect(() => {
    if (!open) return undefined;
    const outside = (event: PointerEvent) => {
      const target = event.target as Node;
      if (!menu.current?.contains(target) && !button.current?.contains(target)) setOpen(false);
    };
    document.addEventListener('pointerdown', outside);
    return () => document.removeEventListener('pointerdown', outside);
  }, [open]);

  function closeAndReturn() {
    setOpen(false);
    button.current?.focus();
  }

  function onButtonKey(event: KeyboardEvent) {
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();
      setOpen(true);
    }
  }

  function onMenuKey(event: KeyboardEvent) {
    const list = items();
    const index = list.indexOf(document.activeElement as HTMLElement);
    if (event.key === 'ArrowDown') list[(index + 1) % list.length]?.focus();
    else if (event.key === 'ArrowUp') list[(index - 1 + list.length) % list.length]?.focus();
    else if (event.key === 'Home') list[0]?.focus();
    else if (event.key === 'End') list[list.length - 1]?.focus();
    else if (event.key === 'Escape') closeAndReturn();
    else if (event.key === 'Tab') {
      setOpen(false);
      return;
    } else return;
    event.preventDefault();
  }

  return (
    <div className="user-menu">
      <button
        ref={button}
        type="button"
        className="user-menu-button"
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls="user-menu-list"
        aria-label={`${displayName}, account menu`}
        onClick={() => setOpen((v) => !v)}
        onKeyDown={onButtonKey}
      >
        <span className="avatar avatar-small" aria-hidden="true">{initialsOf(displayName)}</span>
        <span className="user-menu-name">{firstName}</span>
        <svg aria-hidden="true" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.6" strokeLinecap="round" strokeLinejoin="round">
          <path d="M6 9l6 6 6-6" />
        </svg>
      </button>
      {open && (
        <div id="user-menu-list" ref={menu} className="user-menu-list" role="menu" aria-label="Account" onKeyDown={onMenuKey}>
          <Link role="menuitem" tabIndex={-1} to="/account">Account</Link>
          <Link role="menuitem" tabIndex={-1} to="/bookings">My bookings</Link>
          {hasRole('ADMIN') && <Link role="menuitem" tabIndex={-1} to="/admin">Admin</Link>}
          <button role="menuitem" tabIndex={-1} type="button" onClick={() => { setOpen(false); logout(); }}>Sign out</button>
        </div>
      )}
    </div>
  );
}
