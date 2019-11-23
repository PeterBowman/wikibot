$( function () {
	var $content, $results, $summaryLimit, $summaryStart, $summaryEnd, $timestamp, $total, $paginators,
		$container = $( '#mw-content-text' ),
		URL = 'plwikt-missing-polish-examples/api',
		TIMEOUT = 5000,
		currentLimit = plwiktMissingPolishExamples.limit,
		currentOffset = plwiktMissingPolishExamples.offset,
		disableClicks = false,
		initialRequest = true,
		defaultTipsyOptions = {
			gravity: $.fn.tipsy.autoNS,
			html: true,
			width: 500
		};
	
	function queryNodes( $el ) {
		$content = $el.find( '#plwikt-missing-polish-examples-content' );
		$results = $el.find( '#plwikt-missing-polish-examples-results' );
		$summaryLimit = $el.find( '#plwikt-missing-polish-examples-limit' );
		$summaryStart = $el.find( '#plwikt-missing-polish-examples-start' );
		$summaryEnd = $el.find( '#plwikt-missing-polish-examples-end' );
		$botTimestamp = $el.find( '#plwikt-missing-polish-examples-bottimestamp' );
		$dumpTimestamp = $el.find( '#plwikt-missing-polish-examples-dumptimestamp' );
		$total = $el.find( '#plwikt-missing-polish-examples-total' );
		$paginators = $el.find( '.paginator' );
	}
	
	function makeRequest( params ) {
		return $.ajax( {
			dataType: 'json',
			url: URL + params,
			timeout: TIMEOUT
		} );
	}
	
	function readPaginatorData( $el ) {
		switch ( $el.parent().attr( 'class' ) ) {
			case 'paginator-prev':
				currentOffset -= currentLimit;
				break;
			case 'paginator-next':
				currentOffset += currentLimit;
				break;
			case 'paginator-limits':
				currentLimit = $el.text();
				break;
		}
		
		currentOffset = Math.max( currentOffset, 0 );
		currentLimit = Math.max( currentLimit, 0 );
	}
	
	function handlePaginators( data ) {
		$paginators.find( '.paginator-prev-value, .paginator-next-value' ).text( currentLimit );
		
		$paginators.find( '.paginator-prev, .paginator-next' ).each( function () {
			var $this = $( this ),
				$a = $this.find( 'a' ),
				isNext = $this.hasClass( 'paginator-next' );
			
			if ( !isNext && currentOffset === 0 || isNext && currentOffset + currentLimit > data.total ) {
				$a.replaceWith( $a.html() );
			} else {
				if ( !$a.length ) {
					$a = $( '<a>' )
						.html( $this.html() )
						.appendTo( $this.empty() );
				}
				
				$a.attr( 'href', '?' + $.param( {
					offset: currentOffset + currentLimit * ( isNext ? 1 : -1 ),
					limit: currentLimit
				} ) );
			}
		} );
		
		$paginators.find( '.paginator-limits > a' ).each( function () {
			var $this = $( this );
			
			$this.attr( 'href', '?' + $.param( {
				offset: currentOffset,
				limit: $this.text()
			} ) );
		} );
	}
	
	function updateResults( data ) {
		$botTimestamp.text( data.bottimestamp );
		$dumpTimestamp.text( data.dumptimestamp );
		$total.text( data.total );
		
		$summaryLimit.text( currentLimit );
		$summaryStart.text( currentOffset + 1 );
		$summaryEnd.text( Math.min( currentOffset + currentLimit, data.total ) );
		
		data.results.length > plwiktMissingPolishExamples.columnThreshold
			? $results.addClass( 'column-list' )
			: $results.removeClass( 'column-list' );
		
		$results.html( $.map( data.results, function ( item ) {
			var out = '<a class="wikilink" target="_blank" title="' + item.title +
				'" href="https://pl.wiktionary.org/wiki/' + encodeURI( item.title ) + '#pl' +
				'" data-target="' + item.title + '" data-href="https://pl.wiktionary.org/"' +
				' data-section="polski">' + item.title + '</a>';
			
			out += ' <span class="plwikt-missing-polish-examples-item" data-titles="' + item.backlinkTitles.join( '|' ) +
				'" data-sections="' + item.backlinkSections.join( '|' ) + '">(' + item.backlinks + ')</span>';
			
			return '<li>' + out + '</li>';
		} ).join( '' ) ).attr( 'start', currentOffset + 1 );
	}
	
	function pushHistoryState( data, url ) {
		var searchValues;
		
		if ( !window.history ) {
			return;
		}
		
		searchValues = {
			offset: currentOffset,
			limit: currentLimit
		};
		
		history[ url !== undefined ? 'pushState' : 'replaceState' ]( $.extend( {
			data: data
		}, searchValues ), '', location.origin + location.pathname + (
			url || location.search || '?' + $.param( searchValues )
		) );
	}
	
	function makeTipsy( index, el ) {
		var i,
			$el = $( el ),
			titles = $el.data( 'titles' ).split( '|' ),
			sections = $el.data( 'sections' ).split( '|' ),
			$ul = $( '<ul>' );
		
		for ( i = 0; i < titles.length; i++ ) {
			$( '<li>' )
				.text( titles[ i ] + ' (' + sections[ i ] + ')' )
				.appendTo( $ul );
		}
		
		$( el ).tipsy( $.extend( {}, defaultTipsyOptions, {
			title: function () {
				return $ul.get( 0 ).outerHTML;
			}
		} ) ).css( 'cursor', 'help' );
	}
	
	$container.on( 'click', '.paginator a', function ( evt ) {
		var $this = $( this ),
			href = $this.attr( 'href' );
		
		if ( disableClicks ) {
			return false;
		}
		
		evt.preventDefault();
		
		$content.addClass( 'content-loading' );
		disableClicks = true;
		
		return $.when(
			makeRequest( href ),
			initialRequest ? makeRequest( location.search ) : null
		).then( function ( data, initial ) {
			if ( initialRequest ) {
				pushHistoryState( initial[ 0 ] );
				initialRequest = false;
			}
			
			readPaginatorData( $this );
			handlePaginators( data[ 0 ] );
			updateResults( data[ 0 ] );
			$content.removeClass( 'content-loading' );
			disableClicks = false;
			pushHistoryState( data[ 0 ], href );
			$results.find( '.plwikt-missing-polish-examples-item' ).each( makeTipsy );
		},  function ( jqXHR, textStatus, errorThrown ) {
			location.search = href;
		} );
	} );
	
	window.onpopstate = function ( evt ) {
		if ( evt.state ) {
			currentOffset = evt.state.offset;
			currentLimit = evt.state.limit;
			handlePaginators( evt.state.data );
			updateResults( evt.state.data );
			queryNodes( $container );
		}
	};
	
	queryNodes( $container );
	
	$results.find( '.plwikt-missing-polish-examples-item' ).each( makeTipsy );
} );
