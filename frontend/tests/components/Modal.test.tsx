import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Modal } from "../../app/components/Modal";

describe("Modal", () => {
  it("exposes a labelled dialog and locks page scroll while open", () => {
    const { unmount } = render(
      <Modal onClose={vi.fn()} label="Example">
        <p>Body</p>
      </Modal>
    );
    const dialog = screen.getByRole("dialog", { name: "Example" });
    expect(dialog).toHaveClass("modal-content");
    expect(document.body.style.overflow).toBe("hidden");
    unmount();
    expect(document.body.style.overflow).toBe("");
  });

  it("applies an extra class to the content box", () => {
    render(
      <Modal onClose={vi.fn()} label="Wide" className="csv-wizard">
        <p>Body</p>
      </Modal>
    );
    expect(screen.getByRole("dialog")).toHaveClass("modal-content", "csv-wizard");
  });

  it("closes on backdrop click but not on content click", async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    render(
      <Modal onClose={onClose} label="Example">
        <button type="button">Inside</button>
      </Modal>
    );

    await user.click(screen.getByText("Inside"));
    expect(onClose).not.toHaveBeenCalled();

    // The backdrop is the dialog's parent element.
    await user.click(screen.getByRole("dialog").parentElement!);
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it("closes on Escape by default, and not when closeOnEscape is false", async () => {
    const user = userEvent.setup();
    const onClose = vi.fn();
    const { rerender } = render(
      <Modal onClose={onClose} label="Example">
        <p>Body</p>
      </Modal>
    );
    await user.keyboard("{Escape}");
    expect(onClose).toHaveBeenCalledTimes(1);

    rerender(
      <Modal onClose={onClose} label="Example" closeOnEscape={false}>
        <p>Body</p>
      </Modal>
    );
    await user.keyboard("{Escape}");
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});
