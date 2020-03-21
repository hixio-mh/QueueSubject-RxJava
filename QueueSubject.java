package com.xxxxx.xxxx;

import android.util.Log;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.reactivex.Observer;
import io.reactivex.disposables.Disposable;
import io.reactivex.plugins.RxJavaPlugins;
import io.reactivex.subjects.Subject;
//
// Created by Morteza on 2/1/2020.
//
public class QueueSubject<T> extends Subject<T> {

    /**
     * Creates a {@link QueueSubject} without any initial items.
     *
     * @param <T> type of items emitted by the relay
     * @return the constructed {@link QueueSubject}
     */
    public static <T> QueueSubject<T> create() {
        //noinspection unchecked
        return new QueueSubject();
    }

    /**
     * Creates a {@link QueueSubject} with the given initial items.
     *
     * @param initialItems varargs initial items in the queue
     * @param <T>     type of items emitted by the relay
     * @return the constructed {@link QueueSubject}
     */
    public static <T> QueueSubject<T> createDefault(T... initialItems) {
        return new QueueSubject<T>(initialItems);
    }

    private QueueSubject(T... initialItems) {
        for (T item : initialItems) {
            if (item == null) {
                throw new NullPointerException("item == null");
            }
            queue.offer(item);
        }
    }

    @Override
    public boolean hasObservers() {
        return subscriber.get() != null;
    }

    @Override
    public boolean hasThrowable() {
        return done && error != null;
    }

    @Override
    public boolean hasComplete() {
        return done && error == null;
    }

    @Override
    public Throwable getThrowable() {
        if (done) {
            return error;
        }
        return null;
    }

    @Override
    protected void subscribeActual(Observer<? super T> observer) {
        QueueDisposable<T> qs = new QueueDisposable<T>(observer, this);
        observer.onSubscribe(qs);
        if (!done) {
            set(qs);
            qs.drain(queue);
        } else {
            Throwable ex = error;
            if (ex != null) {
                qs.onError(ex);
            } else {
                qs.onComplete();
            }
        }
    }

    @Override
    public void onSubscribe(Disposable d) {
        if (done) {
            d.dispose();
        }
    }

    @Override
    public void onNext(T value) {
        if (value == null) {
            onError(new NullPointerException("value == null"));
            return;
        }
        if (done) {
            return;
        }
        queue.add(value);
        QueueDisposable<T> qs = subscriber.get();
        if (qs != null && !qs.isDisposed()) {
            qs.drain(queue);
        }
    }

    @Override
    public void onError(Throwable e) {
        if (done) {
            RxJavaPlugins.onError(e);
            return;
        }

        if (e == null) {
            e = new NullPointerException("onError called with null. Null values are generally not allowed in 2.x operators and sources.");
        }
        error = e;
        done = true;
        QueueDisposable<T> qs = subscriber.get();
        if (qs != null) {
            qs.drain(queue);
            qs.onError(e);
        }
    }

    @Override
    public void onComplete() {
        if (done) {
            return;
        }
        done = true;
        QueueDisposable<T> qs = subscriber.get();
        if (qs != null) {
            qs.drain(queue);
            qs.onComplete();
        }
    }

    private void set(QueueDisposable<T> qs) {
        QueueDisposable<T> current = subscriber.get();
        if (current != null) {
            current.dispose();
        }
        subscriber.set(qs);
    }

    public int getQueueSize() {
        return queue.size();
    }

    public void clear() {
        queue.clear();
    }

    private void remove() {
        QueueDisposable<T> current = subscriber.get();
        if (current != null && current.cancelled.get()) {
            subscriber.compareAndSet(current, null);
        }
    }

    public void onContinue() {
        QueueDisposable<T> qs = subscriber.get();
        if (qs != null && !qs.isDisposed()) {
            qs.onContinue();
            qs.drain(queue);
        }
    }

    static final class QueueDisposable<T> extends AtomicInteger implements Disposable {

        QueueDisposable(Observer<? super T> actual, QueueSubject<T> state) {
            this.actual = actual;
            this.state = state;
        }

        void onContinue() {
            isDrawing.compareAndSet(true, false);
        }

        void onError(Throwable e) {
            if (cancelled.get()) {
                RxJavaPlugins.onError(e);
            } else {
                actual.onError(e);
            }
        }

        void onComplete() {
            if (!cancelled.get()) {
                actual.onComplete();
            }
        }

        @Override
        public void dispose() {
            Log.e(getClass().getName(), "QueueDisposable :: dispose");
            synchronized (state) {
                if (cancelled.compareAndSet(false, true)) {
                    state.remove();
                }
            }
        }

        @Override
        public boolean isDisposed() {
            return cancelled.get();
        }

        void drain(Queue<T> queue) {
            if (getAndIncrement() != 0) {
                return;
            }
            int missed = 1;
            while (!cancelled.get()) {
                for (; ; ) {
                    if (cancelled.get()) {
                        return;
                    }
                    synchronized (state) {

                        if (isDrawing.get()) {
                            break;
                        }

                        T item = queue.poll();
                        if (item == null) {
                            break;
                        }
                        actual.onNext(item);
                        isDrawing.lazySet(true);
                    }
                }

                missed = addAndGet(-missed);
                if (missed == 0) {
                    break;
                }
            }
            synchronized (state) {
                isDrawing.compareAndSet(true, false);
            }
        }

        final Observer<? super T> actual;
        final QueueSubject<T> state;
        final AtomicBoolean cancelled = new AtomicBoolean();
        final AtomicBoolean isDrawing = new AtomicBoolean(false);
    }

    //    private static Handler mHandler = new Handler();
    private final Queue<T> queue = new ConcurrentLinkedQueue<>();
    private final AtomicReference<QueueDisposable<T>> subscriber = new AtomicReference<>();
    private Throwable error;
    private volatile boolean done;
}
