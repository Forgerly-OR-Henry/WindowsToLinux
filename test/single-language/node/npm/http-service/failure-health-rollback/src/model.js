class Summary {
  constructor(items) { this.items = Object.freeze([...items]); }
  get total() { return this.items.reduce((sum, item) => sum + item, 0); }
  toJSON() { return { status: 'ok', items: this.items, total: this.total }; }
}
module.exports = { Summary };
