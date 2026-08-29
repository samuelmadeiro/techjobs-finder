import type { JobSummary, PageResponse } from "../api/types";
import { JobCard } from "./JobCard";
import { Pagination } from "./Pagination";

interface Props {
  page: PageResponse<JobSummary>;
  onOpen: (job: JobSummary) => void;
  onPageChange: (page: number) => void;
}

export function JobList({ page, onOpen, onPageChange }: Props) {
  return (
    <div>
      <ul className="space-y-3">
        {page.content.map((job) => (
          <li key={job.id}>
            <JobCard job={job} onOpen={onOpen} />
          </li>
        ))}
      </ul>

      <Pagination
        page={page.page}
        totalPages={page.totalPages}
        last={page.last}
        onChange={onPageChange}
      />
    </div>
  );
}
