// Category combobox island, built on Zag.js's *vanilla* adapter (no framework).
//
// Compare with resources/public/combobox.js (the hand-rolled one): there, WE own
// the filtering, highlight math, keyboard map, ARIA, focus, and live-region. Here
// Zag's combobox state machine owns all of that — role=combobox/listbox/option,
// aria-activedescendant, aria-expanded, typeahead, scroll-into-view, screen-reader
// announcements (@zag-js/live-region), dismissable/interact-outside — and we only
// write the glue: build the DOM, apply the prop-getters Zag hands us, and persist.
//
// This file IS the data point: how much glue, what it costs to bundle, and what
// a11y we get for free vs. hand-rolling.

import * as combobox from "@zag-js/combobox"
import { normalizeProps, spreadProps, VanillaMachine } from "@zag-js/vanilla"

interface Item { code: string; label: string }

const categories: Item[] = [...document.querySelectorAll("#cat-options li")]
  .map((li) => { const c = li.textContent!.trim(); return { code: c, label: c } })

const collection = (items: Item[]) =>
  combobox.collection({ items, itemToValue: (i) => i.code, itemToString: (i) => i.label })

let current: { root: HTMLElement; machine: VanillaMachine<any>; cell: HTMLElement } | null = null

function open(cell: HTMLElement) {
  close()
  let options = categories.slice()

  const root = document.createElement("div")
  root.className = "zag-combobox"
  root.innerHTML =
    `<div class="combobox-control"><input class="combobox-input" placeholder="Filter category…"/></div>` +
    `<div class="combobox-positioner"><ul class="combobox-content"></ul></div>`
  const r = cell.getBoundingClientRect()
  Object.assign(root.style, { position: "fixed", left: `${r.left}px`, top: `${r.top}px`, minWidth: `${r.width}px` })
  document.body.appendChild(root)

  const machine = new VanillaMachine(combobox.machine, {
    id: "cat-combo",
    get collection() { return collection(options) },
    open: true,
    openOnClick: true,
    inputBehavior: "autohighlight",
    onOpenChange(d: any) { if (!d.open) close(true) },
    onInputValueChange({ inputValue }: any) {
      const q = inputValue.toLowerCase()
      const f = categories.filter((i) => i.label.toLowerCase().includes(q))
      options = f.length ? f : categories.slice()
    },
    onValueChange({ value }: any) { if (value[0]) commit(cell, value[0]) },
  })

  const get = (sel: string) => root.querySelector<HTMLElement>(sel)!
  const render = () => {
    const api = combobox.connect(machine.service, normalizeProps)
    spreadProps(root, api.getRootProps(), machine.scope.id)
    spreadProps(get(".combobox-control"), api.getControlProps(), machine.scope.id)
    spreadProps(get(".combobox-input"), api.getInputProps(), machine.scope.id)
    spreadProps(get(".combobox-positioner"), api.getPositionerProps(), machine.scope.id)
    const content = get(".combobox-content")
    spreadProps(content, api.getContentProps(), machine.scope.id)
    content.innerHTML = ""
    for (const item of options) {
      const li = document.createElement("li")
      li.className = "combobox-item"
      li.textContent = item.label
      spreadProps(li, api.getItemProps({ item }), machine.scope.id)
      content.appendChild(li)
    }
  }
  machine.subscribe(render)
  machine.start()
  render()

  const input = get(".combobox-input")
  // App-specific glue (same for any widget impl): Tab hands focus back to the grid.
  input.addEventListener("keydown", (e) => {
    if (e.key === "Tab") {
      e.preventDefault()
      const c = cell
      close()
      c.dispatchEvent(new KeyboardEvent("keydown", { key: "Tab", shiftKey: e.shiftKey, bubbles: true }))
    }
  }, true)
  input.focus()
  current = { root, machine, cell }
}

function commit(cell: HTMLElement, value: string) {
  cell.querySelector(".cell-view")!.textContent = value
  const txId = cell.dataset.cell!.split(":")[0]
  fetch(`/tx/${txId}/category`, {
    method: "PUT", headers: { "content-type": "application/json" }, body: JSON.stringify({ category: value }),
  })
  close(true)
}

function close(refocus?: boolean) {
  if (!current) return
  const { root, machine, cell } = current
  machine.stop()
  root.remove()
  current = null
  if (refocus) cell.focus()
}

document.getElementById("grid")!.addEventListener("click", (e) => {
  const td = (e.target as HTMLElement).closest<HTMLElement>(".combo-cell")
  if (td) open(td)
})
document.addEventListener("open-combo", (e: any) => open(e.detail.td))

;(window as any).__comboOpen = () => !!current
console.log("zag combobox island ready: " + categories.length + " categories")
