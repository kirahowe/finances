/**
 * Drag-and-drop utility - generic reorderable list management.
 *
 * Pure utility following Unix philosophy:
 * - Does one thing well (drag-and-drop state management)
 * - Works with any array type
 * - No dependencies on application logic or UI framework
 * - Composable with other utilities
 */

/**
 * State of a drag-and-drop operation
 */
export interface DragDropState {
  draggedIndex: number | null;
  isDragging: boolean;
}

/**
 * Callback for state changes
 */
type StateChangeCallback = (state: DragDropState) => void;

/**
 * Callback for reorder events
 */
type ReorderCallback<T> = (
  reorderedItems: T[],
  fromIndex: number,
  toIndex: number
) => void;

/**
 * Reorders an array by moving an item from one index to another.
 * Returns a new array without mutating the original.
 *
 * @param items - The array to reorder
 * @param fromIndex - The index to move from
 * @param toIndex - The index to move to
 * @returns New reordered array, or original if indices are invalid
 */
export function reorderArray<T>(
  items: T[],
  fromIndex: number,
  toIndex: number
): T[] {
  // Validation
  if (fromIndex === toIndex) {
    return items;
  }

  if (
    fromIndex < 0 ||
    fromIndex >= items.length ||
    toIndex < 0 ||
    toIndex >= items.length
  ) {
    return items;
  }

  // Create new array and perform reorder
  const result = [...items];
  const [removed] = result.splice(fromIndex, 1);
  result.splice(toIndex, 0, removed);

  return result;
}

/**
 * Creates a drag-and-drop manager for handling list reordering.
 *
 * @returns Manager with methods for drag operations
 */
export function createDragDropManager<T>() {
  let state: DragDropState = {
    draggedIndex: null,
    isDragging: false,
  };

  const subscribers: Set<StateChangeCallback> = new Set();

  function notifySubscribers() {
    subscribers.forEach((callback) => callback(state));
  }

  return {
    /**
     * Get current drag-drop state
     */
    getState(): DragDropState {
      return { ...state };
    },

    /**
     * Subscribe to state changes
     * @returns Unsubscribe function
     */
    subscribe(callback: StateChangeCallback): () => void {
      subscribers.add(callback);
      return () => {
        subscribers.delete(callback);
      };
    },

    /**
     * Start dragging an item at the given index
     */
    startDrag(index: number): void {
      state = {
        draggedIndex: index,
        isDragging: true,
      };
      notifySubscribers();
    },

    /**
     * Handle drag over event at a specific index
     * Calls onReorder if the index is different from current dragged index
     */
    dragOver(
      index: number,
      items: T[],
      onReorder: ReorderCallback<T>
    ): void {
      if (!state.isDragging || state.draggedIndex === null) {
        return;
      }

      if (state.draggedIndex === index) {
        return;
      }

      const reordered = reorderArray(items, state.draggedIndex, index);
      onReorder(reordered, state.draggedIndex, index);

      // Update draggedIndex to reflect new position
      state = {
        ...state,
        draggedIndex: index,
      };
      notifySubscribers();
    },

    /**
     * End the drag operation
     */
    endDrag(): void {
      state = {
        draggedIndex: null,
        isDragging: false,
      };
      notifySubscribers();
    },
  };
}
