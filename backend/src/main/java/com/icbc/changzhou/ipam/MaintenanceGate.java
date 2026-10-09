package com.icbc.changzhou.ipam;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Covers every API request, including authentication and its incidental writes. */
final class MaintenanceGate {
  private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
  private final AtomicBoolean requested = new AtomicBoolean();
  private volatile boolean blocked;

  Permit enterApi() {
    if (isMaintaining()) return null;
    lock.readLock().lock();
    if (isMaintaining()) { lock.readLock().unlock(); return null; }
    return new Permit(false);
  }

  Permit enterMaintenance() throws InterruptedException {
    if (!requested.compareAndSet(false, true)) throw new IllegalStateException("系统正在维护。");
    try { lock.writeLock().lockInterruptibly(); return new Permit(true); }
    catch (InterruptedException e) { requested.set(false); throw e; }
  }

  boolean isMaintaining() { return blocked || requested.get(); }
  void keepBlocked() { blocked = true; }

  final class Permit implements AutoCloseable {
    private final boolean exclusive;
    private boolean closed;
    private Permit(boolean exclusive) { this.exclusive = exclusive; }
    @Override public void close() {
      if (closed) return;
      closed = true;
      if (exclusive) { requested.set(false); lock.writeLock().unlock(); }
      else lock.readLock().unlock();
    }
  }
}
