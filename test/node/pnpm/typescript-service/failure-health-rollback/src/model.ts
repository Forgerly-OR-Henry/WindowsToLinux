export class Summary {
  readonly items: readonly number[];
  constructor(items: number[]) { this.items = Object.freeze([...items]); }
  get total() { return this.items.reduce((sum, item) => sum + item, 0); }
  toJSON() { return { status: 'ok', items: this.items, total: this.total }; }
}
