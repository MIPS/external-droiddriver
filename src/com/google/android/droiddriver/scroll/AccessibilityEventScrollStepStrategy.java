/*
 * Copyright (C) 2013 DroidDriver committers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.droiddriver.scroll;

import android.app.UiAutomation;
import android.app.UiAutomation.AccessibilityEventFilter;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import com.google.android.droiddriver.DroidDriver;
import com.google.android.droiddriver.UiElement;
import com.google.android.droiddriver.actions.SwipeAction;
import com.google.android.droiddriver.exceptions.UnrecoverableException;
import com.google.android.droiddriver.finders.Finder;
import com.google.android.droiddriver.scroll.Direction.Axis;
import com.google.android.droiddriver.scroll.Direction.DirectionConverter;
import com.google.android.droiddriver.scroll.Direction.PhysicalDirection;
import com.google.android.droiddriver.util.Logs;

import java.util.concurrent.TimeoutException;

/**
 * A {@link ScrollStepStrategy} that determines whether more scrolling is
 * possible by checking the {@link AccessibilityEvent} returned by
 * {@link android.app.UiAutomation}.
 * <p>
 * This implementation behaves just like the <a href=
 * "http://developer.android.com/tools/help/uiautomator/UiScrollable.html"
 * >UiScrollable</a> class. It may not work in all cases. For instance,
 * sometimes {@link android.support.v4.widget.DrawerLayout} does not send
 * correct {@link AccessibilityEvent}s after scrolling.
 * </p>
 */
public class AccessibilityEventScrollStepStrategy implements ScrollStepStrategy {
  /**
   * Stores the data if we reached end at the last {@link #scroll}. If the data
   * match when a new scroll is requested, we can return immediately.
   */
  private static class EndData {
    private Finder containerFinderAtEnd;
    private PhysicalDirection directionAtEnd;

    public boolean match(Finder containerFinder, PhysicalDirection direction) {
      return containerFinderAtEnd == containerFinder && directionAtEnd == direction;
    }

    public void set(Finder containerFinder, PhysicalDirection direction) {
      containerFinderAtEnd = containerFinder;
      directionAtEnd = direction;
    }

    public void reset() {
      set(null, null);
    }
  }

  /**
   * This filter allows us to grab the last accessibility event generated for a
   * scroll up to {@code scrollEventTimeoutMillis}.
   */
  private static class LastScrollEventFilter implements AccessibilityEventFilter {
    private AccessibilityEvent lastEvent;

    @Override
    public boolean accept(AccessibilityEvent event) {
      if ((event.getEventType() & AccessibilityEvent.TYPE_VIEW_SCROLLED) != 0) {
        // Recycle the current last event.
        if (lastEvent != null) {
          lastEvent.recycle();
        }
        lastEvent = AccessibilityEvent.obtain(event);
      }
      // Return false to collect events until scrollEventTimeoutMillis has
      // elapsed.
      return false;
    }

    public AccessibilityEvent getLastEvent() {
      return lastEvent;
    }
  }

  private final UiAutomation uiAutomation;
  private final long scrollEventTimeoutMillis;
  private final DirectionConverter directionConverter;
  private final EndData endData = new EndData();

  public AccessibilityEventScrollStepStrategy(UiAutomation uiAutomation,
      long scrollEventTimeoutMillis, DirectionConverter converter) {
    this.uiAutomation = uiAutomation;
    this.scrollEventTimeoutMillis = scrollEventTimeoutMillis;
    this.directionConverter = converter;
  }

  @Override
  public boolean scroll(DroidDriver driver, Finder containerFinder,
      final PhysicalDirection direction) {
    // Check if we've reached end after last scroll.
    if (endData.match(containerFinder, direction)) {
      return false;
    }

    AccessibilityEvent event = doScrollAndReturnEvent(driver.on(containerFinder), direction);
    if (detectEnd(event, direction.axis())) {
      endData.set(containerFinder, direction);
      Logs.log(Log.DEBUG, "reached scroll end with event: " + event);
    }

    // Clean up the event after use.
    if (event != null) {
      event.recycle();
    }

    // Even if event == null, that does not mean scroll has no effect!
    // Some views may not emit correct events when the content changed.
    return true;
  }

  // Copied from UiAutomator.
  // AdapterViews have indices we can use to check for the beginning.
  protected boolean detectEnd(AccessibilityEvent event, Axis axis) {
    if (event == null) {
      return true;
    }

    if (event.getFromIndex() != -1 && event.getToIndex() != -1 && event.getItemCount() != -1) {
      return event.getFromIndex() == 0 || (event.getItemCount() - 1) == event.getToIndex();
    }
    if (event.getScrollX() != -1 && event.getScrollY() != -1) {
      if (axis == Axis.VERTICAL) {
        return event.getScrollY() == 0 || event.getScrollY() == event.getMaxScrollY();
      } else if (axis == Axis.HORIZONTAL) {
        return event.getScrollX() == 0 || event.getScrollX() == event.getMaxScrollX();
      }
    }

    // This case is different from UiAutomator.
    return event.getFromIndex() == -1 && event.getToIndex() == -1 && event.getItemCount() == -1
        && event.getScrollX() == -1 && event.getScrollY() == -1;
  }

  @Override
  public final DirectionConverter getDirectionConverter() {
    return directionConverter;
  }

  @Override
  public String toString() {
    return String.format("AccessibilityEventScrollStepStrategy{scrollEventTimeoutMillis=%d}",
        scrollEventTimeoutMillis);
  }

  @Override
  public void beginScrolling(DroidDriver driver, Finder containerFinder, Finder itemFinder,
      PhysicalDirection direction) {
    endData.reset();
  }

  @Override
  public void endScrolling(DroidDriver driver, Finder containerFinder, Finder itemFinder,
      PhysicalDirection direction) {}

  protected AccessibilityEvent doScrollAndReturnEvent(final UiElement container,
      final PhysicalDirection direction) {
    LastScrollEventFilter filter = new LastScrollEventFilter();
    try {
      uiAutomation.executeAndWaitForEvent(new Runnable() {
        @Override
        public void run() {
          doScroll(container, direction);
        }
      }, filter, scrollEventTimeoutMillis);
    } catch (IllegalStateException e) {
      throw new UnrecoverableException(e);
    } catch (TimeoutException e) {
      // We expect this because LastScrollEventFilter.accept always returns
      // false.
    }
    return filter.getLastEvent();
  }

  @Override
  public void doScroll(final UiElement container, final PhysicalDirection direction) {
    SwipeAction.toScroll(direction).perform(container.getInjector(), container);
  }

  /**
   * Some widgets may not always fire correct {@link AccessibilityEvent}.
   * Detecting end by null event is safer (at the cost of a extra scroll) than
   * examining indices.
   */
  public static class NullAccessibilityEventScrollStepStrategy extends
      AccessibilityEventScrollStepStrategy {

    public NullAccessibilityEventScrollStepStrategy(UiAutomation uiAutomation,
        long scrollEventTimeoutMillis, DirectionConverter converter) {
      super(uiAutomation, scrollEventTimeoutMillis, converter);
    }

    @Override
    protected boolean detectEnd(AccessibilityEvent event, Axis axis) {
      return event == null;
    }
  }
}