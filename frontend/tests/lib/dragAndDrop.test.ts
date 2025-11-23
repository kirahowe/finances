import { describe, it, expect, vi } from 'vitest';
import {
  createDragDropManager,
  reorderArray,
  type DragDropState,
} from '../../app/lib/dragAndDrop';

describe('dragAndDrop', () => {
  describe('reorderArray', () => {
    it('moves item from lower to higher index', () => {
      const items = ['a', 'b', 'c', 'd'];
      const result = reorderArray(items, 1, 3);
      expect(result).toEqual(['a', 'c', 'd', 'b']);
    });

    it('moves item from higher to lower index', () => {
      const items = ['a', 'b', 'c', 'd'];
      const result = reorderArray(items, 3, 1);
      expect(result).toEqual(['a', 'd', 'b', 'c']);
    });

    it('returns same array when fromIndex equals toIndex', () => {
      const items = ['a', 'b', 'c'];
      const result = reorderArray(items, 1, 1);
      expect(result).toEqual(items);
      expect(result).toBe(items); // Should be the same reference
    });

    it('does not mutate original array', () => {
      const items = ['a', 'b', 'c'];
      const original = [...items];
      reorderArray(items, 0, 2);
      expect(items).toEqual(original);
    });

    it('handles single item array', () => {
      const items = ['a'];
      const result = reorderArray(items, 0, 0);
      expect(result).toEqual(['a']);
    });

    it('handles empty array', () => {
      const items: string[] = [];
      const result = reorderArray(items, 0, 0);
      expect(result).toEqual([]);
    });

    it('returns original array when fromIndex is out of bounds', () => {
      const items = ['a', 'b', 'c'];
      const result = reorderArray(items, 5, 1);
      expect(result).toBe(items);
    });

    it('returns original array when toIndex is out of bounds', () => {
      const items = ['a', 'b', 'c'];
      const result = reorderArray(items, 1, 5);
      expect(result).toBe(items);
    });

    it('returns original array when fromIndex is negative', () => {
      const items = ['a', 'b', 'c'];
      const result = reorderArray(items, -1, 1);
      expect(result).toBe(items);
    });

    it('returns original array when toIndex is negative', () => {
      const items = ['a', 'b', 'c'];
      const result = reorderArray(items, 1, -1);
      expect(result).toBe(items);
    });

    it('works with objects', () => {
      const items = [{ id: 1 }, { id: 2 }, { id: 3 }];
      const result = reorderArray(items, 0, 2);
      expect(result).toEqual([{ id: 2 }, { id: 3 }, { id: 1 }]);
    });

    it('works with numbers', () => {
      const items = [10, 20, 30, 40];
      const result = reorderArray(items, 1, 3);
      expect(result).toEqual([10, 30, 40, 20]);
    });
  });

  describe('createDragDropManager', () => {
    it('initializes with default state', () => {
      const manager = createDragDropManager<string>();
      expect(manager.getState()).toEqual({
        draggedIndex: null,
        isDragging: false,
      });
    });

    it('starts drag operation', () => {
      const manager = createDragDropManager<string>();
      const onStateChange = vi.fn();
      manager.subscribe(onStateChange);

      manager.startDrag(2);

      expect(manager.getState()).toEqual({
        draggedIndex: 2,
        isDragging: true,
      });
      expect(onStateChange).toHaveBeenCalledWith({
        draggedIndex: 2,
        isDragging: true,
      });
    });

    it('ends drag operation', () => {
      const manager = createDragDropManager<string>();
      const onStateChange = vi.fn();
      manager.subscribe(onStateChange);

      manager.startDrag(1);
      manager.endDrag();

      expect(manager.getState()).toEqual({
        draggedIndex: null,
        isDragging: false,
      });
      expect(onStateChange).toHaveBeenCalledTimes(2);
    });

    it('calls onReorder when dragging over different index', () => {
      const items = ['a', 'b', 'c', 'd'];
      const onReorder = vi.fn();
      const manager = createDragDropManager<string>();

      manager.startDrag(1);
      manager.dragOver(3, items, onReorder);

      expect(onReorder).toHaveBeenCalledWith(['a', 'c', 'd', 'b'], 1, 3);
    });

    it('does not call onReorder when dragging over same index', () => {
      const items = ['a', 'b', 'c'];
      const onReorder = vi.fn();
      const manager = createDragDropManager<string>();

      manager.startDrag(1);
      manager.dragOver(1, items, onReorder);

      expect(onReorder).not.toHaveBeenCalled();
    });

    it('does not call onReorder when not dragging', () => {
      const items = ['a', 'b', 'c'];
      const onReorder = vi.fn();
      const manager = createDragDropManager<string>();

      manager.dragOver(1, items, onReorder);

      expect(onReorder).not.toHaveBeenCalled();
    });

    it('updates draggedIndex when dragging to new position', () => {
      const items = ['a', 'b', 'c', 'd'];
      const onReorder = vi.fn();
      const manager = createDragDropManager<string>();

      manager.startDrag(1);
      expect(manager.getState().draggedIndex).toBe(1);

      manager.dragOver(3, items, onReorder);
      expect(manager.getState().draggedIndex).toBe(3);
    });

    it('allows multiple subscribers', () => {
      const manager = createDragDropManager<string>();
      const subscriber1 = vi.fn();
      const subscriber2 = vi.fn();

      manager.subscribe(subscriber1);
      manager.subscribe(subscriber2);

      manager.startDrag(0);

      expect(subscriber1).toHaveBeenCalled();
      expect(subscriber2).toHaveBeenCalled();
    });

    it('allows unsubscribe', () => {
      const manager = createDragDropManager<string>();
      const subscriber = vi.fn();

      const unsubscribe = manager.subscribe(subscriber);
      manager.startDrag(0);
      expect(subscriber).toHaveBeenCalledTimes(1);

      unsubscribe();
      manager.endDrag();
      expect(subscriber).toHaveBeenCalledTimes(1); // No new call
    });

    it('handles rapid drag operations', () => {
      const items = ['a', 'b', 'c', 'd', 'e'];
      const onReorder = vi.fn();
      const manager = createDragDropManager<string>();

      manager.startDrag(0);
      manager.dragOver(1, items, onReorder);
      manager.dragOver(2, items, onReorder);
      manager.dragOver(3, items, onReorder);
      manager.dragOver(4, items, onReorder);

      expect(onReorder).toHaveBeenCalledTimes(4);
    });

    it('resets state correctly after multiple operations', () => {
      const items = ['a', 'b', 'c'];
      const onReorder = vi.fn();
      const manager = createDragDropManager<string>();

      manager.startDrag(0);
      manager.dragOver(2, items, onReorder);
      manager.endDrag();

      manager.startDrag(1);
      manager.dragOver(0, items, onReorder);
      manager.endDrag();

      expect(manager.getState()).toEqual({
        draggedIndex: null,
        isDragging: false,
      });
    });

    it('handles edge case of dragging first item to last', () => {
      const items = ['a', 'b', 'c'];
      const onReorder = vi.fn();
      const manager = createDragDropManager<string>();

      manager.startDrag(0);
      manager.dragOver(2, items, onReorder);

      expect(onReorder).toHaveBeenCalledWith(['b', 'c', 'a'], 0, 2);
    });

    it('handles edge case of dragging last item to first', () => {
      const items = ['a', 'b', 'c'];
      const onReorder = vi.fn();
      const manager = createDragDropManager<string>();

      manager.startDrag(2);
      manager.dragOver(0, items, onReorder);

      expect(onReorder).toHaveBeenCalledWith(['c', 'a', 'b'], 2, 0);
    });

    it('works with complex objects', () => {
      const items = [
        { id: 1, name: 'First' },
        { id: 2, name: 'Second' },
        { id: 3, name: 'Third' },
      ];
      const onReorder = vi.fn();
      const manager = createDragDropManager<typeof items[0]>();

      manager.startDrag(0);
      manager.dragOver(2, items, onReorder);

      expect(onReorder).toHaveBeenCalledWith(
        [
          { id: 2, name: 'Second' },
          { id: 3, name: 'Third' },
          { id: 1, name: 'First' },
        ],
        0,
        2
      );
    });
  });
});
