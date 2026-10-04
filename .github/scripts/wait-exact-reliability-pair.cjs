// Reuse the producer for this exact commit; never dispatch, cancel or rebuild it.
module.exports = async ({github, context, core}) => {
  for (let attempt = 0; attempt < 90; attempt++) {
    let runs;
    try {
      ({data: {workflow_runs: runs}} = await github.rest.actions.listWorkflowRuns({
        ...context.repo, workflow_id: 'paired-functional-test.yml',
        head_sha: context.sha, branch: context.ref.replace('refs/heads/', ''), per_page: 30
      }));
    } catch (error) {
      if (![429, 500, 502, 503, 504].includes(error.status)) throw error;
      core.warning('Transient producer status: ' + error.status);
      await new Promise(resolve => setTimeout(resolve, 12000)); continue;
    }
    const run = runs.find(value => value.head_sha === context.sha && value.event === 'push');
    if (run?.status === 'completed') {
      if (run.conclusion !== 'success') throw new Error('Exact producer failed: ' + run.html_url);
      core.exportVariable('BAIZE_PAIRED_RUN', run.id);
      core.exportVariable('BAIZE_PAIRED_NAME', 'BaiZe-functional-test+' + context.sha.slice(0, 8) + '-Paired-Test');
      core.info('Using exact signed producer: ' + run.html_url); return;
    }
    core.info('Continuing exact producer ' + context.sha + ': ' + (run?.status || 'not yet listed'));
    await new Promise(resolve => setTimeout(resolve, 12000));
  }
  throw new Error('Exact producer did not finish within the bounded wait');
};
