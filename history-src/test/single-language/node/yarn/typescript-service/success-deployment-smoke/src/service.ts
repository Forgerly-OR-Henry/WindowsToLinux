import { z } from "zod";
import { Summary } from "./model";
const itemSchema = z
  .string()
  .regex(/^[0-9]{1,10}$/)
  .transform(Number)
  .pipe(z.number().int().min(0).max(10000));
const valuesSchema = z.array(itemSchema).min(1).max(20);
export function summarize(raw: string | null): Summary {
  const values = raw === null ? ["1", "2", "3"] : raw.split(",");
  return new Summary(valuesSchema.parse(values));
}
