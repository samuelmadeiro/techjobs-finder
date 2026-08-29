import { Building2, ExternalLink } from "lucide-react";
import type { CompanySummary } from "../api/types";

interface Props {
  company?: CompanySummary;
  size?: "sm" | "md";
}

/** Empresa com iniciais quando não há logo. Link externo só quando o site é conhecido. */
export function CompanyInfo({ company, size = "sm" }: Props) {
  const box = size === "md" ? "size-12 text-base" : "size-10 text-sm";

  if (!company?.name) {
    return (
      <div className="flex items-center gap-3">
        <span
          className={`flex ${box} shrink-0 items-center justify-center rounded-control bg-slate-100 text-slate-500`}
          aria-hidden="true"
        >
          <Building2 className="size-5" />
        </span>
        <span className="text-sm text-slate-500">Empresa não informada</span>
      </div>
    );
  }

  const initials = company.name
    .split(/\s+/)
    .slice(0, 2)
    .map((word) => word.charAt(0).toUpperCase())
    .join("");

  return (
    <div className="flex min-w-0 items-center gap-3">
      <span
        className={`flex ${box} shrink-0 items-center justify-center overflow-hidden rounded-control bg-brand-50 font-semibold text-brand-700 ring-1 ring-brand-100`}
        aria-hidden="true"
      >
        {company.logoUrl ? (
          <img src={company.logoUrl} alt="" className="size-full object-cover" loading="lazy" />
        ) : (
          initials
        )}
      </span>

      <div className="min-w-0">
        <p
          className={`truncate font-medium text-slate-700 ${size === "md" ? "text-base" : "text-sm"}`}
          title={company.name}
        >
          {company.name}
        </p>
        {size === "md" && company.website && (
          <a
            href={company.website}
            target="_blank"
            rel="noopener noreferrer nofollow"
            className="mt-0.5 inline-flex items-center gap-1 text-xs text-brand-600 hover:underline"
          >
            {company.website.replace(/^https?:\/\//, "")}
            <ExternalLink className="size-3" aria-hidden="true" />
          </a>
        )}
      </div>
    </div>
  );
}
